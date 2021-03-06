package com.adonai.wallet;


import android.app.AlertDialog;
import android.content.DialogInterface;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.ListView;

import com.adonai.wallet.database.DbProvider;
import com.adonai.wallet.database.EntityDao;
import com.adonai.wallet.entities.Entity;

import java.sql.SQLException;

import static com.adonai.wallet.WalletPreferencesFragment.ASK_FOR_DELETE;

/**
 * All wallet master fragments must extend this class
 *
 * @author adonai
 */
public abstract class WalletBaseListFragment extends Fragment {

    protected ListView mEntityList;

    final public WalletBaseActivity getWalletActivity() {
        return (WalletBaseActivity) getActivity();
    }

    protected class EntityDeleteListener {

        private final int mMessageId;

        public EntityDeleteListener(int deleteMessageResource) {
            mMessageId = deleteMessageResource;
        }

        public void handleRemoveAttempt(final Entity entity) {
            final LayoutInflater inflater = LayoutInflater.from(getActivity());
            if (getWalletActivity().getPreferences().getBoolean(ASK_FOR_DELETE, true)) { // check to assure we want to delete entity
                final CheckBox dontShowAgain = (CheckBox) inflater.inflate(R.layout.remember_choice_checkbox, null, false);
                dontShowAgain.setOnCheckedChangeListener(new WalletPreferencesFragment.DontAskForDelete(getWalletActivity()));
                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.confirm_action)
                        .setMessage(mMessageId)
                        .setView(dontShowAgain)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                deleteItem(entity);
                            }
                        }).create().show();
            } else // just delete without confirm
                deleteItem(entity);
        }

        @SuppressWarnings("unchecked")
        protected <T extends Entity> void deleteItem(T entity) {
            try {
                ((EntityDao) DbProvider.getHelper().getDao(entity.getClass())).delete(entity);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    protected abstract class EntityChoice implements DialogInterface.OnClickListener {

        protected final int mItemPosition;

        public EntityChoice(int mItemPosition) {
            this.mItemPosition = mItemPosition;
        }
    }
}
