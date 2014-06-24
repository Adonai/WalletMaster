package com.adonai.wallet;

import android.app.DatePickerDialog;
import android.app.DialogFragment;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.TextView;

import com.adonai.wallet.entities.UUIDSpinnerAdapter;

import java.util.Calendar;
import java.util.Date;

import static com.adonai.wallet.Utils.VIEW_DATE_FORMAT;


/**
 * All wallet master dialog fragments must extend this class
 *
 * @author adonai
 */
public class WalletBaseDialogFragment extends DialogFragment {

    final public WalletBaseActivity getWalletActivity() {
        return (WalletBaseActivity) getActivity();
    }

    protected class DatePickerListener implements View.OnClickListener, View.OnFocusChangeListener {

        private Calendar mSelectedTime = Calendar.getInstance();
        private final EditText mText;

        public DatePickerListener(EditText text) {
            mText = text;
        }

        @Override
        public void onClick(View v) {
            handleClick();
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if(!hasFocus)
                return;

            handleClick();
        }

        private void handleClick() {
            DatePickerDialog.OnDateSetListener listener = new DatePickerDialog.OnDateSetListener() {
                @Override
                public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                    mSelectedTime.set(year, monthOfYear, dayOfMonth);
                    mText.setText(VIEW_DATE_FORMAT.format(mSelectedTime.getTime()));
                }
            };
            new DatePickerDialog(getActivity(), listener, mSelectedTime.get(Calendar.YEAR), mSelectedTime.get(Calendar.MONTH), mSelectedTime.get(Calendar.DAY_OF_MONTH)).show();
        }

        public Calendar getCalendar() {
            return mSelectedTime;
        }

        public void setCalendar(Date mNow) {
            this.mSelectedTime.setTime(mNow);
        }
    }

    protected class AccountsWithNoneAdapter extends UUIDSpinnerAdapter {
        private final int mNoneResId;

        public AccountsWithNoneAdapter(int noneTextResId) {
            super(getActivity(), getWalletActivity().getEntityDAO().getAccountCursor());
            this.mNoneResId = noneTextResId;
        }

        @Override
        public View newView(int position, View convertView, ViewGroup parent, int resId) {
            final View view;

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(mContext);
                view = inflater.inflate(resId, parent, false);
            } else
                view = convertView;

            final TextView name = (TextView) view.findViewById(android.R.id.text1);
            if(position == 0)
                name.setText(mNoneResId);
            else {
                mCursor.moveToPosition(position - 1);
                name.setText(mCursor.getString(1));
            }

            return view;
        }

        @Override
        public int getCount() {
            return super.getCount() + 1;
        }

        @Override
        public Cursor getItem(int position) {
            if(position == 0)
                return null;
            else
                return super.getItem(position - 1);
        }

        @Override
        public String getItemUUID(int position) {
            if(position == 0)
                return "None";
            else
                return super.getItemUUID(position - 1);
        }

        @Override
        public int getPosition(String id) {
            int parent = super.getPosition(id);
            if(parent >= 0)
                return parent + 1;
            else
                return parent;
        }
    }
}
