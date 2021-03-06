package com.adonai.wallet;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.adonai.wallet.database.AbstractAsyncLoader;
import com.adonai.wallet.database.DbProvider;
import com.adonai.wallet.database.EntityDao;
import com.adonai.wallet.entities.Account;
import com.adonai.wallet.adapters.UUIDCursorAdapter;
import com.j256.ormlite.android.AndroidDatabaseResults;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

import static com.adonai.wallet.Utils.convertDpToPixel;

/**
 * Fragment responsible for showing accounts list
 * and their context actions
 *
 * @author Adonai
 */
public class AccountsFragment extends WalletBaseListFragment {

    private TextView budgetSum;
    private final EntityDeleteListener mAccountDeleter = new EntityDeleteListener(R.string.really_delete_account);

    private RetrieveContentsCallback mContentRetrieveCallback = new RetrieveContentsCallback();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setHasOptionsMenu(true);

        final View rootView = inflater.inflate(R.layout.accounts_flow, container, false);
        assert rootView != null;

        mEntityList = (ListView) rootView.findViewById(R.id.account_list);
        getLoaderManager().initLoader(Utils.ACCOUNTS_LOADER, Bundle.EMPTY, mContentRetrieveCallback);
        budgetSum = (TextView) rootView.findViewById(R.id.account_sum);

        return rootView;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.accounts_flow, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_add_account:
                final AccountDialogFragment accountCreate = new AccountDialogFragment();
                accountCreate.show(getFragmentManager(), "accCreate");
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private class AccountsAdapter extends UUIDCursorAdapter<Account> {
        public AccountsAdapter() {
            super(getActivity(), Account.class);
        }

        @Override
        @SuppressWarnings("deprecation") // for compat with older APIs
        public View getView(int position, View convertView, ViewGroup parent) {
            final View view;
            final LayoutInflater inflater = LayoutInflater.from(mContext);

            if (convertView == null)
                view = inflater.inflate(R.layout.account_list_item, parent, false);
            else
                view = convertView;

            try {
                ((AndroidDatabaseResults) mCursor.getRawResults()).moveAbsolute(position);
                Account acc = mCursor.current();

                final int accColor = acc.getColor();
                final float[] rounds = new float[8];
                Arrays.fill(rounds, convertDpToPixel(10f, mContext));
                final ShapeDrawable mDrawable = new ShapeDrawable(new RoundRectShape(rounds, null, null));
                mDrawable.getPaint().setShader(new LinearGradient(0, 0, mContext.getResources().getDisplayMetrics().widthPixels, 0,
                        Color.argb(50, Color.red(accColor), Color.green(accColor), Color.blue(accColor)),
                        Color.argb(0, Color.red(accColor), Color.green(accColor), Color.blue(accColor)), Shader.TileMode.CLAMP));
                view.findViewById(R.id.main_content_layout).setBackgroundDrawable(mDrawable);

                final TextView name = (TextView) view.findViewById(R.id.account_name_label);
                name.setText(acc.getName());
                final TextView description = (TextView) view.findViewById(R.id.account_description_label);
                description.setText(acc.getDescription());
                final TextView currency = (TextView) view.findViewById(R.id.account_currency_label);
                currency.setText(acc.getCurrency().getCode());
                final TextView amount = (TextView) view.findViewById(R.id.account_amount_label);
                amount.setText(acc.getAmount().toPlainString());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            return view;
        }
    }

    private class AccountLongClickListener implements AdapterView.OnItemLongClickListener {

        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, final long id) {
            final AlertDialog.Builder alertDialog = new AlertDialog.Builder(getActivity());
            alertDialog.setItems(R.array.entity_choice_common, new AccountChoice(position)).setTitle(R.string.select_action).create().show();
            return true;
        }
    }

    private class AccountClickListener implements AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final AccountsAdapter adapter = (AccountsAdapter) parent.getAdapter();
            final UUID accountID = adapter.getItemUUID(position);
            final Account managed = DbProvider.getHelper().getAccountDao().queryForId(accountID);
            if(managed != null) {
                OperationDialogFragment.forAccount(managed.getId().toString()).show(getFragmentManager(), "createOperation");
            }

        }
    }

    private class AccountChoice extends EntityChoice {

        public AccountChoice(int mItemPosition) {
            super(mItemPosition);
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            final AccountsAdapter adapter = (AccountsAdapter) mEntityList.getAdapter();
            final UUID accID = adapter.getItemUUID(mItemPosition);
            final Account acc = DbProvider.getHelper().getAccountDao().queryForId(accID);
            switch (which) {
                case 0: // modify
                    AccountDialogFragment.forAccount(acc.getId().toString()).show(getFragmentManager(), "accModify");
                    break;
                case 1: // delete
                    mAccountDeleter.handleRemoveAttempt(acc);
                    break;
            }
        }
    }

    private class RetrieveContentsCallback implements LoaderManager.LoaderCallbacks<AccountsAdapter> {
        @Override
        public Loader<AccountsAdapter> onCreateLoader(int id, @NonNull final Bundle args) {
            AbstractAsyncLoader<AccountsAdapter> toRegister = new AbstractAsyncLoader<AccountsAdapter>(getActivity()) {
                @Nullable
                @Override
                public AccountsAdapter loadInBackground() {
                    if(!isStarted()) // task was cancelled
                        return null;
        
                    // check the DB for accounts
                    return new AccountsAdapter();
                }

                @Override
                protected void onForceLoad() {
                    if(mData != null) { // close old adapter before loading new one
                        mData.closeCursor();
                    }
                    super.onForceLoad();
                }
            };
            EntityDao<Account> accDao = DbProvider.getHelper().getDao(Account.class);
            accDao.registerObserver(toRegister);
            return toRegister;
        }

        @Override
        public void onLoadFinished(Loader<AccountsAdapter> loader, AccountsAdapter data) {
            mEntityList.setAdapter(data);
            mEntityList.setOnItemLongClickListener(new AccountLongClickListener());
            mEntityList.setOnItemClickListener(new AccountClickListener());
        }

        @Override
        public void onLoaderReset(Loader<AccountsAdapter> loader) {
        }

    }

}
