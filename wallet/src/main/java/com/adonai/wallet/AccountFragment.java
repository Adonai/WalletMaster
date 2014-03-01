package com.adonai.wallet;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.TextView;

/**
 * A placeholder fragment containing a simple view.
 */
public class AccountFragment extends Fragment {

    ListView mAccountList;
    TextView budgetSum;
    /**
     * The fragment argument representing the section number for this
     * fragment.
     */
    private static final String ARG_SECTION_NUMBER = "section_number";

    /**
     * Returns a new instance of this fragment for the given section
     * number.
     */
    public static AccountFragment newInstance(int sectionNumber) {
        AccountFragment fragment = new AccountFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    public WalletBaseActivity getWalletActivity() {
        return (WalletBaseActivity) getActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setHasOptionsMenu(true);

        final View rootView = inflater.inflate(R.layout.accounts_flow, container, false);
        assert rootView != null;

        mAccountList = (ListView) rootView.findViewById(R.id.account_list);
        budgetSum = (TextView) rootView.findViewById(R.id.account_sum);

        AccountsAdapter adapter = new AccountsAdapter(getActivity(), getWalletActivity().getEntityDAO().getAcountCursor(), false);
        getWalletActivity().getEntityDAO().registerDatabaseListener(DatabaseDAO.ACCOUNTS_TABLE_NAME, adapter);
        mAccountList.setAdapter(adapter);

        return rootView;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        ((MainFlow) activity).onSectionAttached(getArguments().getInt(ARG_SECTION_NUMBER));
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.accounts_flow, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_add_account:
                final CreateAccountDialogFragment accountCreate = new CreateAccountDialogFragment();
                accountCreate.show(getFragmentManager(), "accCreate");
                break;
            default :
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    public class AccountsAdapter extends CursorAdapter implements DatabaseDAO.DatabaseListener {
        public AccountsAdapter(Context context, Cursor c, boolean autoRequery) {
            super(context, c, autoRequery);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup viewGroup) {
            final LayoutInflater inflater = LayoutInflater.from(context);
            final View newView = inflater.inflate(R.layout.account_list_item, viewGroup, false);

            final TextView name = (TextView) newView.findViewById(R.id.account_name_label);
            name.setText(cursor.getString(1));
            final TextView description = (TextView) newView.findViewById(R.id.account_description_label);
            description.setText(cursor.getString(2));
            final TextView amount = (TextView) newView.findViewById(R.id.account_amount_label);
            amount.setText(cursor.getString(3));

            return newView;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final TextView name = (TextView) view.findViewById(R.id.account_name_label);
            name.setText(cursor.getString(1));
            final TextView description = (TextView) view.findViewById(R.id.account_description_label);
            description.setText(cursor.getString(2));
            final TextView amount = (TextView) view.findViewById(R.id.account_amount_label);
            amount.setText(cursor.getString(4));
        }

        @Override
        public long getItemId(int position) {
            getCursor().moveToPosition(position);

            return getCursor().getLong(0);
        }

        @Override
        public void handleUpdate() {
            changeCursor(getWalletActivity().getEntityDAO().getAcountCursor());
        }
    }


}
