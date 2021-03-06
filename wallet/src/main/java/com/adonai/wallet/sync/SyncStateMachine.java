package com.adonai.wallet.sync;

import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.adonai.wallet.R;
import com.adonai.wallet.WalletConstants;
import com.adonai.wallet.database.DbProvider;
import com.adonai.wallet.database.PersistManager;
import com.adonai.wallet.entities.Account;
import com.adonai.wallet.entities.Category;
import com.adonai.wallet.entities.Entity;
import com.adonai.wallet.entities.Operation;
import com.j256.ormlite.table.DatabaseTable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static com.adonai.wallet.sync.SyncProtocol.SyncRequest;
import static com.adonai.wallet.sync.SyncProtocol.SyncResponse;

/**
 * In short, synchronization scheme is as follows:
 *
 *| Client sends register/authentication packet ---------------------------------------------------------------->|
 *|                                                                                                              |
 *| <----------------------- Server replies with ok/error. If we have error here, communication ends immediately |
 *|                                                                                                              |
 *| Client sends account request containing all known to him synced account ID --------------------------------->|
 *|                                                                                                              |
 *| *** Server parses request and makes a list of added/removed/modified IDs on server based on its data         |
 *| *** Added accounts are transferred fully and contain GUIDs if present, removed accounts contain only GUIDs   |
 *|                                                                                                              |
 *| <--------------------------------- Server replies with account response containing added/removed accounts    |
 *|                                                                                                              |
 *| *** Client adds and removes mentioned accounts locally. Thus on client we are up-to-date with server         |
 *| *** Client makes his own account response of data that was added/removed/modified locally                    |
 *|                                                                                                              |
 *| Client sends account response containing locally deleted/added/modified accounts --------------------------->|
 *|                                                                                                              |
 *| *** Server adds or removes the client data to/from server database. Thus we are now almost synced            |
 *| *** Server expects no more data about current entities                                                       |
 *|                                                                                                              |
 *| <-------------------------------- Server replies with account acknowledge containing new timestamp-----------|
 *|                                                                                                              |
 *| *** Client updates its last sync date                                                                        |
 *| *** Client also purges contents of table that tracks deletions of synced entities                            |
 *|                                                                                                              |
 * *** Now we are synced
 *
 * *** This procedure is repeated for each entity type (accounts, operations, categories)
 *
 *
 */
public class SyncStateMachine {
    public enum State {
        INIT,
        REGISTER(true),
        REGISTER_SENT,
        REGISTER_ACK,
        REGISTER_DENIED,

        SYNC_START(true),
        AUTH_SENT,
        AUTH_ACK,
        AUTH_DENIED,

        ACC_REQ(true),
        ACC_REQ_SENT,
        ACC_REQ_ACK,

        CAT_REQ(true),
        CAT_REQ_SENT,
        CAT_REQ_ACK,

        OP_REQ(true),
        OP_REQ_SENT,
        OP_REQ_ACK;


        private boolean needsAction;

        State(boolean needsAction) {
            this.needsAction = needsAction;
        }

        State() {
            needsAction = false;
        }

        public boolean isActionNeeded() {
            return needsAction;
        }
    }

    /**
     * These fields are updated during syncing
     */
    private State state;                    // to know our state
    private Socket mSocket;                 // to communicate with server
    private PersistManager mPersistContext; // to make DB calls

    private final Context mContext;
    private final SyncResult mSyncResult;
    private final android.accounts.Account mAccount;
    
    private final SharedPreferences mPreferences;
    private final Object mSyncLock = new Object();
    private final Handler mHandler;

    /**
     * Data extracted from bundle
     */
    private final boolean isManualSync;


    public SyncStateMachine(Context context, SyncResult sr, android.accounts.Account account, Bundle extras) {
        final HandlerThread thr = new HandlerThread("WalletSyncThread");
        thr.start();
        mHandler = new Handler(thr.getLooper(), new SocketCallback());

        mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        mContext = context;
        mAccount = account;
        mSyncResult = sr;

        isManualSync = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL);
    }

    /*
    public void shutdown() {
        interruptSync(mContext.getString(R.string.force_shutdown));
        mHandler.getLooper().quit();
    }*/

    private void setState(State state) {
        this.state = state;
        if(state.isActionNeeded()) // state is for internal handling, not for notifying
            mHandler.sendEmptyMessage(state.ordinal());
    }

    public boolean isSyncing() {
        return state != State.INIT;
    }
    
    public void startSync() {
        setState(SyncStateMachine.State.SYNC_START);
    }
    
    public void syncBlocking() {
        setState(SyncStateMachine.State.SYNC_START);
        synchronized (mSyncLock) {
            try {
                mSyncLock.wait();
            } catch (InterruptedException e) {
                Log.e("SYNC", "Interrupted!");
            }
        }
    }

    private class SocketCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message msg) {
            final State state = State.values()[msg.what];
            try {
                switch (state) {
                    case SYNC_START: { // at this state, account should be already configured and accessible from preferences!
                        initSync();

                        final InputStream is = mSocket.getInputStream();
                        final OutputStream os = mSocket.getOutputStream();

                        sendAuthRequest(os);
                        handleAuthResponse(is);
                        break;
                    }
                    case ACC_REQ: { // at this state we must be authorized on server
                        final InputStream is = mSocket.getInputStream();
                        final OutputStream os = mSocket.getOutputStream();

                        sendLastTimestamp(os, Account.class);
                        setState(State.ACC_REQ_SENT);

                        final SyncProtocol.EntityResponse serverSide = SyncProtocol.EntityResponse.parseDelimitedFrom(is);
                        setState(State.ACC_REQ_ACK);

                        // handle modified entities - check if we updated them too...
                        List<Account> changedAccs = new ArrayList<>(serverSide.getModifiedList().size());
                        for(final SyncProtocol.Entity entity : serverSide.getModifiedList()) {
                            Account remote = Account.fromProtoEntity(entity);
                            Account local = mPersistContext.getAccountDao().queryForId(remote.getId());
                            if(local == null) { // not found on client, but exists remotely, should create on client
                                mPersistContext.getEntityDao(Account.class).createByServer(remote);
                            } else if (!local.isDirty()) { // updated on server but not on client, replace local with remote
                                mPersistContext.getEntityDao(Account.class).updateByServer(remote);
                            } else { // update on server and on client, should resolve conflicts
                                remote = mergeAccounts(remote, local, (Account) local.getBackup());
                                mPersistContext.getEntityDao(Account.class).update(remote); // do not reset dirty flag
                            }
                            changedAccs.add(remote);
                        }

                        // prepare response
                        SyncProtocol.EntityResponse.Builder serverUpdate = SyncProtocol.EntityResponse.newBuilder();

                        // adding newly inserted entities
                        List<Account> newAccounts = mPersistContext.getAccountDao().queryBuilder().where().isNull("last_modified").query();
                        newAccounts.removeAll(changedAccs);
                        for(Account newAcc : newAccounts) {
                            serverUpdate.addAdded(newAcc.toProtoEntity());
                        }
                        // adding modified entities
                        //List<Account> dirtyAccounts = mPersistContext.getAccountDao().queryBuilder().where().isNotNull("backup").query(); // TODO: restore with 4.49
                        List<Account> dirtyAccounts = mPersistContext.getAccountDao().queryBuilder().where().raw("backup IS NOT NULL").query();
                        for(Account dirtyAcc : dirtyAccounts) {
                            serverUpdate.addModified(dirtyAcc.toProtoEntity());
                        }

                        serverUpdate.build().writeDelimitedTo(os);
                        final SyncProtocol.EntityAck ack = SyncProtocol.EntityAck.parseDelimitedFrom(is);
                        Date newTimestamp = new Date(ack.getNewServerTimestamp());
                        // updating local entities with new timestamp
                        for(Account newAcc : newAccounts) {
                            newAcc.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Account.class).updateByServer(newAcc);
                        }
                        for(Account dirtyAcc : dirtyAccounts) {
                            dirtyAcc.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Account.class).updateByServer(dirtyAcc);
                        }
                        for(Account changedAcc : changedAccs) {
                            changedAcc.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Account.class).updateByServer(changedAcc);
                        }
                        setState(State.CAT_REQ);
                        break;
                    }
                    case CAT_REQ: {
                        final InputStream is = mSocket.getInputStream();
                        final OutputStream os = mSocket.getOutputStream();

                        sendLastTimestamp(os, Category.class);
                        setState(State.CAT_REQ_SENT);

                        final SyncProtocol.EntityResponse serverSide = SyncProtocol.EntityResponse.parseDelimitedFrom(is);
                        setState(State.CAT_REQ_ACK);

                        // handle modified entities - check if we updated them too...
                        List<Category> changedCategories = new ArrayList<>(serverSide.getModifiedList().size());
                        for(final SyncProtocol.Entity entity : serverSide.getModifiedList()) {
                            final Category remote = Category.fromProtoEntity(entity);
                            final Category local = mPersistContext.getCategoryDao().queryForId(remote.getId());
                            if(local == null) { // not found on client, but exists remotely, should create on client
                                mPersistContext.getEntityDao(Category.class).createByServer(remote);
                            } else if (!local.isDirty()) { // updated on server but not on client, replace local with remote
                                mPersistContext.getEntityDao(Category.class).updateByServer(remote);
                            } else { // update on server and on client, should resolve conflicts (just take server version for now)
                                mPersistContext.getEntityDao(Category.class).updateByServer(remote);
                            }
                            changedCategories.add(remote);
                        }

                        // prepare response
                        SyncProtocol.EntityResponse.Builder serverUpdate = SyncProtocol.EntityResponse.newBuilder();

                        // adding newly inserted entities
                        List<Category> newCategories = mPersistContext.getCategoryDao().queryBuilder().where().isNull("last_modified").query();
                        newCategories.removeAll(changedCategories);
                        for(Category newCategory : newCategories) {
                            serverUpdate.addAdded(newCategory.toProtoEntity());
                        }
                        // adding modified entities
                        //List<Category> dirtyCategories = mPersistContext.getCategoryDao().queryBuilder().where().isNotNull("backup").query(); // TODO: restore with 4.49
                        List<Category> dirtyCategories = mPersistContext.getCategoryDao().queryBuilder().where().raw("backup IS NOT NULL").query();
                        for(Category dirtyCategory : dirtyCategories) {
                            serverUpdate.addModified(dirtyCategory.toProtoEntity());
                        }

                        serverUpdate.build().writeDelimitedTo(os);
                        final SyncProtocol.EntityAck ack = SyncProtocol.EntityAck.parseDelimitedFrom(is);
                        Date newTimestamp = new Date(ack.getNewServerTimestamp());
                        // updating local entities with new timestamp
                        for(Category newCategory : newCategories) {
                            newCategory.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Category.class).updateByServer(newCategory);
                        }
                        for(Category dirtyCategory : dirtyCategories) {
                            dirtyCategory.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Category.class).updateByServer(dirtyCategory);
                        }
                        for(Category changedCategory : changedCategories) {
                            changedCategory.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Category.class).updateByServer(changedCategory);
                        }
                        setState(State.OP_REQ);
                        break;
                    }
                    case OP_REQ: {
                        final InputStream is = mSocket.getInputStream();
                        final OutputStream os = mSocket.getOutputStream();

                        sendLastTimestamp(os, Operation.class);
                        setState(State.OP_REQ_SENT);

                        final SyncProtocol.EntityResponse serverSide = SyncProtocol.EntityResponse.parseDelimitedFrom(is);
                        setState(State.OP_REQ_ACK);

                        // handle modified entities - check if we updated them too...
                        List<Operation> changedOps = new ArrayList<>(serverSide.getModifiedList().size());
                        for(final SyncProtocol.Entity entity : serverSide.getModifiedList()) {
                            final Operation remote = Operation.fromProtoEntity(entity);
                            final Operation local = mPersistContext.getOperationDao().queryForId(remote.getId());
                            if(local == null) { // not found on client, but exists remotely, should create on client
                                mPersistContext.getEntityDao(Operation.class).createByServer(remote);
                            } else if (!local.isDirty()) { // updated on server but not on client, replace local with remote
                                mPersistContext.getEntityDao(Operation.class).updateByServer(remote);
                            } else { // update on server and on client, should resolve conflicts
                                mPersistContext.getEntityDao(Operation.class).updateByServer(remote); // just take server version
                            }
                            changedOps.add(remote);
                        }

                        // prepare response
                        SyncProtocol.EntityResponse.Builder serverUpdate = SyncProtocol.EntityResponse.newBuilder();

                        // adding newly inserted entities
                        List<Operation> newOperations = mPersistContext.getOperationDao().queryBuilder().where().isNull("last_modified").query();
                        newOperations.removeAll(changedOps);
                        for(Operation newOp : newOperations) {
                            serverUpdate.addAdded(newOp.toProtoEntity());
                        }
                        // adding modified entities
                        //List<Operation> dirtyOperations = mPersistContext.getAccountDao().queryBuilder().where().isNotNull("backup").query(); // TODO: restore with 4.49
                        List<Operation> dirtyOperations = mPersistContext.getOperationDao().queryBuilder().where().raw("backup IS NOT NULL").query();
                        for(Operation dirtyOp : dirtyOperations) {
                            serverUpdate.addModified(dirtyOp.toProtoEntity());
                        }

                        serverUpdate.build().writeDelimitedTo(os);
                        final SyncProtocol.EntityAck ack = SyncProtocol.EntityAck.parseDelimitedFrom(is);
                        Date newTimestamp = new Date(ack.getNewServerTimestamp());
                        // updating local entities with new timestamp
                        for(Operation newOp : newOperations) {
                            newOp.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Operation.class).updateByServer(newOp);
                        }
                        for(Operation dirtyOp : dirtyOperations) {
                            dirtyOp.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Operation.class).updateByServer(dirtyOp);
                        }
                        for(Operation changedOp : changedOps) {
                            changedOp.setLastModified(newTimestamp);
                            mPersistContext.getEntityDao(Operation.class).updateByServer(changedOp);
                        }

                        finishSync();
                        break;
                    }
                }
            } catch (IOException io) {
                mSyncResult.stats.numIoExceptions = 1;
                interruptSync(io.getLocalizedMessage());
            } catch (SQLException sql) {
                mSyncResult.databaseError = true;
                interruptSync(sql.getLocalizedMessage());
            }
            return true;
        }

        private void sendLastTimestamp(OutputStream os, Class<? extends Entity> clazz) throws IOException, SQLException {
            final Long lastServerTime = getLastServerTimestamp(clazz);

            SyncProtocol.EntityRequest.newBuilder()
                    .setLastKnownServerTimestamp(lastServerTime)
                    .build().writeDelimitedTo(os); // sent request
        }

        private void handleAuthResponse(InputStream is) throws IOException {
            final SyncResponse response = SyncResponse.parseDelimitedFrom(is);
            AccountManager accountManager = (AccountManager) mContext.getSystemService(Context.ACCOUNT_SERVICE);
            switch (response.getSyncAck()) {
                case OK:
                    setState(State.AUTH_ACK);
                    setState(State.ACC_REQ);

                    accountManager.setUserData(mAccount, WalletConstants.ACCOUNT_SYNC_KEY, "true");
                    break;
                case AUTH_WRONG:
                    mSyncResult.stats.numAuthExceptions = 1;
                    interruptSync(mContext.getString(R.string.auth_invalid));
                    accountManager.removeAccount(mAccount, null, null); // clear all previous accounts
                    break;
                case ACCOUNT_EXISTS:
                    mSyncResult.stats.numAuthExceptions = 1;
                    interruptSync(mContext.getString(R.string.account_already_exist));
                    accountManager.removeAccount(mAccount, null, null); // clear all previous accounts
                    break;
            }
        }

        private void sendAuthRequest(OutputStream os) throws IOException {
            // fill request
            AccountManager accountManager = (AccountManager) mContext.getSystemService(Context.ACCOUNT_SERVICE);
            String password = accountManager.getPassword(mAccount);
            String isSyncedAlready = accountManager.getUserData(mAccount, WalletConstants.ACCOUNT_SYNC_KEY);
            final SyncRequest.Builder request = SyncRequest.newBuilder()
                    .setAccount(mAccount.name)
                    .setPassword(password);
            if(isSyncedAlready.equals("true")) // already synchronized
                request.setSyncType(SyncRequest.SyncType.MERGE);
            else
                request.setSyncType(SyncRequest.SyncType.REGISTER);

            request.build().writeDelimitedTo(os); // actual sending of request
            os.flush();
        }
    }

    /**
     * Toast needs looper thread to exist so be sure to call this method only from {@link SocketCallback}'s thread
     * @param error error to display to the user
     * @see #mHandler
     */
    private void interruptSync(String error) {
        safeCloseSocket();
        mPersistContext.getWritableDatabase().endTransaction();
        DbProvider.releaseTempHelper();
        setState(State.INIT);
        
        // notify about error
        Toast.makeText(mContext, mContext.getString(R.string.sync_error) + ": " + error, Toast.LENGTH_SHORT).show();

        // if we use blocking sync, this will finish it
        synchronized (mSyncLock) {
            mSyncLock.notify();
        }

        safeQuitThread();
    }

    /**
     * Toast needs looper thread to exist so be sure to call this method only from {@link SocketCallback}'s thread
     * @see #mHandler
     */
    private void finishSync() {
        safeCloseSocket();
        mPersistContext.getWritableDatabase().setTransactionSuccessful();
        mPersistContext.getWritableDatabase().endTransaction();
        DbProvider.releaseTempHelper();
        setState(State.INIT);

        if(isManualSync && !mSyncResult.hasError()) {
            Toast.makeText(mContext, mContext.getString(R.string.sync_completed), Toast.LENGTH_SHORT).show();
        }

        // if we use blocking sync, this will finish it
        synchronized (mSyncLock) {
            mSyncLock.notify();
        }

        safeQuitThread();
    }
    
    private void safeCloseSocket() {
        if(state == State.INIT)
            return; // nothing to do
        
        if(!mSocket.isClosed())
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.e("SYNC", "Unexpected error while closing socket", e); // should not happen, swallow
            } 
    }

    /**
     * Finish after 5 seconds to let the toast show its contents
     */
    private void safeQuitThread() {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mHandler.getLooper().quit();  // finish handler thread
            }
        }, 5000);
    }

    private void initSync() throws IOException {
        mPersistContext = DbProvider.getTempHelper(mContext);
        mPersistContext.getWritableDatabase().beginTransaction();
            /*DatabaseDAO.getInstance().beginTransaction();*/
        mSocket = new Socket(); // creating socket here!
        //mSocket.setSoTimeout(10000);
        //mSocket.connect(new InetSocketAddress(mPreferences.getString("sync.server", "anticitizen.dhis.org"), 17001));
        mSocket.connect(new InetSocketAddress(mPreferences.getString("sync.server", "192.168.1.165"), 17001));
    }

    private Account mergeAccounts(Account remote, Account local, Account base) {
        final Account result = new Account();
        result.setId(local.getId());
        result.setDeleted(remote.isDeleted());
        result.setLastModified(local.getLastModified());
        result.setBackup(base);

        if(local.getName().equals(base.getName())) // name wasn't changed
            result.setName(remote.getName()); // set name to remote's
        else // name changed locally
            result.setName(local.getName()); // set name to local

        if(local.getDescription().equals(base.getDescription()))
            result.setDescription(remote.getDescription());
        else
            result.setDescription(local.getDescription());

        if(local.getColor().equals(base.getColor()))
            result.setColor(remote.getColor());
        else
            result.setColor(local.getColor());

        if(local.getCurrency().equals(base.getCurrency()))
            result.setCurrency(remote.getCurrency());
        else
            result.setCurrency(local.getCurrency());

        if(local.getAmount().equals(base.getAmount())) // amount wasn't changed locally
            result.setAmount(remote.getAmount());
        else // amount changed locally and remotely - get diff!
            result.setAmount(local.getAmount().subtract(base.getAmount()).add(remote.getAmount()));

        return result;
    }

    private <T extends Entity> long getLastServerTimestamp(Class<T> clazz) throws SQLException {
        String tableName = clazz.getAnnotation(DatabaseTable.class).tableName();
        if(tableName.isEmpty())
            tableName = clazz.getSimpleName().toLowerCase();
        return mPersistContext.getEntityDao(clazz).queryRawValue("select ifnull(max(last_modified), 0) from " + tableName);
    }
}
