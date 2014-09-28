package com.adonai.wallet.entities;

import android.content.Context;
import android.widget.BaseAdapter;

import com.j256.ormlite.dao.CloseableIterator;
import com.j256.ormlite.stmt.QueryBuilder;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Base class for all entity adapters
 * Makes its structure to be suitable for list views
 *
 * Implements methods to handle string ID columns
 *
 * @author Adonai
 */
public abstract class UUIDCursorAdapter<T extends Entity> extends BaseAdapter {

    protected QueryBuilder<T, UUID> mQuery;
    protected CloseableIterator<T> mCursor;
    protected Context mContext;

    public UUIDCursorAdapter(Context context, QueryBuilder<T, UUID> query) {
        try {
            mQuery = query;
            mCursor = mQuery.iterator();
            mContext = context;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getCount() {
        try {
            return (int) mQuery.countOf();
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public T getItem(int position) {
        try {
            mCursor.first();
            return mCursor.moveRelative(position);
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public long getItemId(int position) {
        try {
            mCursor.first();
            T entity = mCursor.moveRelative(position);
            return entity.getId().getLeastSignificantBits();
        } catch (SQLException e) {
            return  -1;
        }
    }

    public UUID getItemUUID(int position) {
        try {
            mCursor.first();
            T entity = mCursor.moveRelative(position);
            return entity.getId();
        } catch (SQLException e) {
            return  null;
        }
    }

    public int getPosition(String uuid) {
        UUID toFind = UUID.fromString(uuid);
        return getPosition(toFind);
    }

    public int getPosition(UUID uuid) {
        try {
            int pos = 0;
            T first = mCursor.first();
            if(first.getId().equals(uuid))
                return pos;

            while (mCursor.hasNext()) {
                ++pos;
                T entity = mCursor.next();
                if(entity.getId().equals(uuid))
                    return pos;
            }
        } catch (SQLException e) {
            return  -1;
        }
        return -1;
    }

    public void closeCursor() {
        mCursor.closeQuietly();
    }
}
