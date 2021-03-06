package com.adonai.wallet.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.adonai.wallet.R;
import com.adonai.wallet.entities.Account;
import com.adonai.wallet.entities.Budget;
import com.adonai.wallet.entities.BudgetItem;
import com.adonai.wallet.entities.Category;
import com.adonai.wallet.entities.Currency;
import com.adonai.wallet.entities.Entity;
import com.adonai.wallet.entities.Operation;
import com.j256.ormlite.android.apptools.OrmLiteSqliteOpenHelper;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.RuntimeExceptionDao;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.support.DatabaseConnection;
import com.j256.ormlite.table.TableUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Base database manager for providing {@link EntityDao}'s
 */
public class PersistManager extends OrmLiteSqliteOpenHelper {

    private static final String TAG = PersistManager.class.getSimpleName();

    //имя файла базы данных который будет храниться в /data/data/APPNAME/DATABASE_NAME
    private static final String DATABASE_NAME ="wallet.db";

    //с каждым увеличением версии, при нахождении в устройстве БД с предыдущей версией будет выполнен метод onUpgrade();
    private static final int DATABASE_VERSION = 7;

    //ссылки на DAO соответсвующие сущностям, хранимым в БД
    private EntityDao<Account> accountDao = null;
    private EntityDao<Budget> budgetDao = null;
    private EntityDao<BudgetItem> budgetItemDao = null;
    private EntityDao<Category> categoryDao = null;
    private Dao<Currency, String> currencyDao = null;
    private EntityDao<Operation> operationDao = null;
    private final Context mContext;

    public PersistManager(Context context){
        super(context,DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }

    //Выполняется, когда файл с БД не найден на устройстве
    @Override
    public void onCreate(SQLiteDatabase db, ConnectionSource connectionSource) {
        try {
            TableUtils.createTable(connectionSource, Account.class);
            TableUtils.createTable(connectionSource, Budget.class);
            TableUtils.createTable(connectionSource, BudgetItem.class);
            TableUtils.createTable(connectionSource, Category.class);
            TableUtils.createTable(connectionSource, Currency.class);
            TableUtils.createTable(connectionSource, Operation.class);

            DatabaseConnection conn = connectionSource.getReadWriteConnection();
            conn.setAutoCommit(false);
            // fill Categories
            final String[] defaultOutcomeCategories = mContext.getResources().getStringArray(R.array.out_categories);
            final String[] defaultIncomeCategories = mContext.getResources().getStringArray(R.array.inc_categories);
            final String[] defaultTransCategories = mContext.getResources().getStringArray(R.array.transfer_categories);

            Category outAdd = null;
            long current = 0; // prefilled categories should have predictable IDs for further sync
            for(final String outCategory : defaultOutcomeCategories) {
                outAdd = new Category();
                outAdd.setId(new UUID(0, ++current));
                outAdd.setName(outCategory);
                outAdd.setType(Category.CategoryType.EXPENSE);
                getCategoryDao().create(outAdd);
            }
            Category inAdd = null;
            for(final String inCategory : defaultIncomeCategories) {
                inAdd = new Category();
                inAdd.setId(new UUID(0, ++current));
                inAdd.setName(inCategory);
                inAdd.setType(Category.CategoryType.INCOME);
                getCategoryDao().create(inAdd);
            }
            Category transAdd = null;
            for(final String transCategory : defaultTransCategories) {
                transAdd = new Category();
                transAdd.setId(new UUID(0, ++current));
                transAdd.setName(transCategory);
                transAdd.setType(Category.CategoryType.TRANSFER);
                getCategoryDao().create(transAdd);
            }

            //fill Currencies
            final InputStream allCurrencies = getClass().getResourceAsStream("/assets/currencies.csv");
            final BufferedReader reader = new BufferedReader(new InputStreamReader(allCurrencies));
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    final String[] tokens = line.split(":");
                    final Currency curr = new Currency();
                    switch (tokens.length) { // switch-case-no-break magic!
                        case 3:
                            curr.setUsedIn(tokens[2]);
                        /* falls through */
                        case 2:
                            curr.setDescription(tokens[1]);
                        /* falls through */
                        case 1:
                            curr.setCode(tokens[0]);
                            break;
                    }
                    getCurrencyDao().create(curr);
                }
            } catch (IOException e) {
                throw new RuntimeException(e); // should not happen!
            }

        } catch (SQLException e) {
            Log.e(TAG, "error creating DB " + DATABASE_NAME);
            throw new RuntimeException(e);
        }
    }

    //Выполняется, когда БД имеет версию отличную от текущей
    @Override
    public void onUpgrade(SQLiteDatabase db, ConnectionSource connectionSource, int oldVer, int newVer) {
        switch (oldVer) {
            case 6:
                db.execSQL("UPDATE account SET backup = NULL");
                db.execSQL("UPDATE category SET backup = NULL");
                db.execSQL("UPDATE operation SET backup = NULL");
        }
    }

    public RuntimeExceptionDao<Account, UUID> getAccountDao() {
        if(accountDao == null) {
            accountDao = getDao(Account.class);
        }
        return new RuntimeExceptionDao<>(accountDao);
    }

    public RuntimeExceptionDao<Budget, UUID> getBudgetDao() {
        if(budgetDao == null) {
            budgetDao = getDao(Budget.class);
        }
        return new RuntimeExceptionDao<>(budgetDao);
    }

    public RuntimeExceptionDao<BudgetItem, UUID> getBudgetItemDao() {
        if(budgetItemDao == null) {
            budgetItemDao = getDao(BudgetItem.class);
        }
        return new RuntimeExceptionDao<>(budgetItemDao);
    }

    public RuntimeExceptionDao<Category, UUID> getCategoryDao() {
        if(categoryDao == null) {
            categoryDao = getDao(Category.class);
        }
        return new RuntimeExceptionDao<>(categoryDao);
    }

    public RuntimeExceptionDao<Currency, String> getCurrencyDao() {
        if(currencyDao == null) {
            currencyDao = getDao(Currency.class);
        }
        return new RuntimeExceptionDao<>(currencyDao);
    }

    public RuntimeExceptionDao<Operation, UUID> getOperationDao() {
        if(operationDao == null) {
            operationDao = getDao(Operation.class);
        }
        return new RuntimeExceptionDao<>(operationDao);
    }

    public <T extends Entity> EntityDao<T> getEntityDao(Class<T> clazz) {
        return getDao(clazz);
    }

    @Override
    public <D extends Dao<T, ?>, T> D getDao(Class<T> clazz) {
        try {
            return super.getDao(clazz);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    //выполняется при закрытии приложения
    @Override
    public void close() {
        super.close();
    }
}
