package com.adonai.wallet.sync;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;

import com.adonai.wallet.DatabaseDAO;
import com.adonai.wallet.R;
import com.adonai.wallet.WalletBaseActivity;
import com.adonai.wallet.WalletConstants;
import com.adonai.wallet.entities.Account;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static com.adonai.wallet.sync.SyncProtocol.EntityRequest;
import static com.adonai.wallet.sync.SyncProtocol.EntityResponse;
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
 *| *** Server parses account request and makes a list of added/removed IDs on server based on its data          |
 *| *** Added accounts are transferred fully and contain GUIDs if present, removed accounts contain only GUIDs   |
 *|                                                                                                              |
 *| <--------------------------------- Server replies with account response containing added/removed accounts    |
 *|                                                                                                              |
 *| *** Client adds and removes mentioned accounts locally. Thus on client we are up-to-date with server         |
 *| *** Client makes his own account response of data that was added/removed locally                             |
 *|                                                                                                              |
 *| Client sends account response containing locally deleted/added accounts ------------------------------------>|
 *|                                                                                                              |
 *| *** Server adds or removes the client data to/from server database. Thus we are now almost synced            |
 *| *** At te end we must send to the client data about GUIDs of newly added accounts                            |
 *| *** Server expects no more data about current entities                                                       |
 *|                                                                                                              |
 *| <-------------------------------- Server replies with account acknowledge containing GUIDs of added accounts.|
 *|                                                                                                              |
 *| *** Client updates its accounts with the data from last packet.                                              |
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

        AUTH(true),
        AUTH_SENT,
        AUTH_ACK,
        AUTH_DENIED,

        ACC_REQ(true),
        ACC_REQ_SENT,
        ACC_REQ_ACK,

        OP_REQ(true),
        OP_REQ_SENT,
        OP_REQ_ACK,

        CAT_REQ(true),
        CAT_REQ_SENT,
        CAT_REQ_ACK;


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

    public interface SyncListener {
        void handleSyncMessage(int what, String errorMsg);
    }

    private State state;
    private final Looper mLooper;
    private final Handler mHandler;
    private Socket mSocket;
    private final List<SyncListener> mListeners = new ArrayList<>(2);
    private final WalletBaseActivity mContext;
    private final SharedPreferences mPreferences;

    public SyncStateMachine(WalletBaseActivity context) {
        HandlerThread thr = new HandlerThread("ServiceThread");
        thr.start();
        mLooper = thr.getLooper();
        mHandler = new Handler(mLooper, new SocketCallback());

        mListeners.add(context);
        mContext = context;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public void registerSyncListener(SyncListener listener) {
        mListeners.add(listener);
    }

    public void unregisterSyncListener(SyncListener listener) {
        mListeners.remove(listener);
    }

    public void notifyListeners(int what, String errorString) {
        for(final SyncListener lsnr : mListeners)
            lsnr.handleSyncMessage(what, errorString);
    }

    public void shutdown() {
        try {
            mSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mLooper.quit();
    }

    public void setState(State state) {
        this.state = state;
        if(state.isActionNeeded()) // state is for internal handling, not for notifying
            mHandler.sendEmptyMessage(state.ordinal());
        else
            notifyListeners(state.ordinal(), null);
    }

    public void setState(State state, String errorMsg) {
        this.state = state;
        if(state.isActionNeeded())
            mHandler.sendEmptyMessage(state.ordinal());
        else
            notifyListeners(state.ordinal(), errorMsg);
    }

    public State getState() {
        return state;
    }

    private class SocketCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message msg) {
            final State state = State.values()[msg.what];
            try {
                switch (state) {
                    case AUTH: { // at this state, account should be already configured and accessible from preferences!
                        if (!mPreferences.contains(WalletConstants.ACCOUNT_NAME_KEY))
                            throw new RuntimeException("No account configured! Can't sync!");

                        mSocket = new Socket(); // creating socket here!
                        mSocket.connect(new InetSocketAddress("10.0.2.2", 17001));

                        final InputStream is = mSocket.getInputStream();
                        final OutputStream os = mSocket.getOutputStream();

                        sendAuthRequest(os);
                        handleAuthResponse(is);
                        break;
                    }
                    case ACC_REQ: { // at this state we must be authorized on server
                        final InputStream is = mSocket.getInputStream();
                        final OutputStream os = mSocket.getOutputStream();
                        sendAccountRequest(os);
                        setState(State.ACC_REQ_SENT);

                        handleAccountResponse(is);
                        setState(State.ACC_REQ_ACK);

                        mergeAccountAck(is, os);
                        setState(State.CAT_REQ);
                        break;
                    }
                    case CAT_REQ: {
                        final InputStream is = mSocket.getInputStream();
                        final OutputStream os = mSocket.getOutputStream();

                        break;
                    }
                }
            } catch (IOException io) {
                setState(State.INIT, io.getMessage());
                if(!mSocket.isClosed())
                    try {
                        mSocket.close();
                    } catch (IOException e) { throw new RuntimeException(e); } // should not happen
            }
            return true;
        }

        private void mergeAccountAck(InputStream is, OutputStream os) throws IOException {
            // send non-synced accounts to server
            final DatabaseDAO.SyncHelper<Account> sHelper = mContext.getEntityDAO().getSyncHelper(Account.class);
            final List<Account> nsAccs = sHelper.getNonSynced();
            final EntityResponse.Builder toSend = EntityResponse.newBuilder();
            for(final Account acc : nsAccs)
                toSend.addEntity(SyncProtocol.Entity.newBuilder().setAccount(Account.toProtoAccount(acc)));
            // send deleted accounts to server
            final List<Long> delAccs = sHelper.getDeletedGUIDs();
            toSend.addAllDeletedID(delAccs);
            toSend.build().writeDelimitedTo(os);
            os.flush();

            // get confirmation of sync
            final SyncProtocol.EntityAck ack = SyncProtocol.EntityAck.parseDelimitedFrom(is);
            final List<Long> ackAccounts = ack.getWrittenGuidList();
            for(int i = 0; i < nsAccs.size(); ++i) { // add GUIDs to previously non-synced accs
                final Account curr = nsAccs.get(i);
                curr.setGuid(ackAccounts.get(i));
                mContext.getEntityDAO().updateAccount(curr);
            }
            sHelper.purgeDeletedGUIDs();
        }

        private void handleAccountResponse(InputStream is) throws IOException {
            // accept account response
            final EntityResponse response = EntityResponse.parseDelimitedFrom(is);
            // delete accounts (that were deleted on server) locally
            final List<Long> deletedAccounts = response.getDeletedIDList();
            for(final Long deleted : deletedAccounts)
                mContext.getEntityDAO().getSyncHelper(Account.class).deleteByGuid(deleted);
            // add accounts (that were added on server) locally
            final List<SyncProtocol.Entity> accounts = response.getEntityList();
            for(final SyncProtocol.Entity acc : accounts) {
                final Account toDatabase = Account.fromProtoAccount(acc.getAccount());
                mContext.getEntityDAO().addAccount(toDatabase);
            }
        }

        private void sendAccountRequest(OutputStream os) throws IOException {
            // send account request with all already synced accounts
            final EntityRequest.Builder request = EntityRequest.newBuilder()
                    .addAllKnownID(mContext.getEntityDAO().getSyncHelper(Account.class).getKnownGUIDs());
            request.build().writeDelimitedTo(os); // actual sending of request
            os.flush();
        }

        private void handleAuthResponse(InputStream is) throws IOException {
            final SyncResponse response = SyncResponse.parseDelimitedFrom(is);
            switch (response.getSyncAck()) {
                case OK:
                    setState(State.AUTH_ACK);
                    setState(State.ACC_REQ);
                    mPreferences.edit().putBoolean(WalletConstants.ACCOUNT_SYNC_KEY, true).commit(); // save auth
                    break;
                case AUTH_WRONG:
                    setState(State.INIT, mContext.getString(R.string.auth_invalid));
                    clearAccountInfo();
                    mSocket.close();
                    break;
                case ACCOUNT_EXISTS:
                    setState(State.INIT, mContext.getString(R.string.account_already_exist));
                    clearAccountInfo();
                    mSocket.close();
                    break;
            }
        }

        private void sendAuthRequest(OutputStream os) throws IOException {
            // fill request
            final SyncRequest.Builder request = SyncRequest.newBuilder()
                    .setAccount(mPreferences.getString(WalletConstants.ACCOUNT_NAME_KEY, ""))
                    .setPassword(mPreferences.getString(WalletConstants.ACCOUNT_PASSWORD_KEY, ""));
            if(mPreferences.getBoolean(WalletConstants.ACCOUNT_SYNC_KEY, false)) // already synchronized
                request.setSyncType(SyncRequest.SyncType.MERGE);
            else
                request.setSyncType(SyncRequest.SyncType.REGISTER);

            request.build().writeDelimitedTo(os); // actual sending of request
            os.flush();
        }

        private void clearAccountInfo() {
            mPreferences.edit()
                    .remove(WalletConstants.ACCOUNT_SYNC_KEY)
                    .remove(WalletConstants.ACCOUNT_NAME_KEY)
                    .remove(WalletConstants.ACCOUNT_PASSWORD_KEY)
                    .commit();
        }
    }
}
