package com.adonai.wallet.entities;

import com.adonai.wallet.database.DbProvider;
import com.adonai.wallet.database.EntityDao;
import com.adonai.wallet.sync.SyncProtocol;
import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.table.DatabaseTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Entity representing an operation. Operations show money flow
 * across accounts and more
 * <p>
 * Required fields:
 * <ol>
 *     <li>category</li>
 *     <li>time</li>
 *     <li>amount</li>
 * </ol>
 * </p>
 * <p>
 * Optional fields:
 * <ol>
 *     <li>description</li>
 *     <li>charge account-----| choose one</li>
 *     <li>beneficiar account--| required</li>
 *     <li>conversion rate</li>
 * </ol>
 * </p>
 * @author adonai
 */
@DatabaseTable(daoClass = EntityDao.class)
public class Operation extends Entity {

    @DatabaseField(canBeNull = false, dataType = DataType.DATE_LONG)
    private Date time;

    @DatabaseField(canBeNull = false)
    private BigDecimal amount;

    @DatabaseField(canBeNull = false, foreign = true, foreignAutoRefresh = true)
    private Category category;

    @DatabaseField
    private String description;

    @DatabaseField(foreign = true, foreignAutoRefresh = true)
    private Account orderer;

    @DatabaseField(foreign = true, foreignAutoRefresh = true)
    private Account beneficiar;

    @DatabaseField(columnName = "converting_rate")
    private BigDecimal convertingRate;

    public Operation() {
    }

    public Operation(BigDecimal amount, Category category) {
        this.amount = amount;
        this.category = category;
    }

    public Account getOrderer() {
        return orderer;
    }

    public void setOrderer(Account orderer) {
        this.orderer = orderer;
    }

    public Account getBeneficiar() {
        return beneficiar;
    }

    public void setBeneficiar(Account beneficiar) {
        this.beneficiar = beneficiar;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getConvertingRate() {
        return convertingRate;
    }

    public void setConvertingRate(BigDecimal convertingRate) {
        this.convertingRate = convertingRate;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Date getTime() {
        return time;
    }

    public void setTime(Date time) {
        this.time = time;
    }

    public BigDecimal getAmountDelivered() {
        if(getConvertingRate() != null)
           return getAmount().divide(getConvertingRate(), 2, RoundingMode.HALF_UP);
        else
            return getAmount();
    }

    public static Operation fromProtoEntity(SyncProtocol.Entity entity) throws SQLException {
        final Operation temp = new Operation();
        temp.setId(UUID.fromString(entity.getID()));
        temp.setDeleted(entity.getDeleted());

        temp.setDescription(entity.getOperation().getDescription());
        temp.setCategory(DbProvider.getHelper().getCategoryDao().queryForId(UUID.fromString(entity.getOperation().getCategoryId())));
        temp.setTime(new Date(entity.getOperation().getTime()));
        if(entity.getOperation().hasOrdererId())
            temp.setOrderer(DbProvider.getHelper().getAccountDao().queryForId(UUID.fromString(entity.getOperation().getOrdererId())));
        if(entity.getOperation().hasBeneficiarId())
            temp.setBeneficiar(DbProvider.getHelper().getAccountDao().queryForId(UUID.fromString(entity.getOperation().getBeneficiarId())));
        temp.setAmount(new BigDecimal(entity.getOperation().getAmount()));
        if(entity.getOperation().hasConvertingRate())
            temp.setConvertingRate(BigDecimal.valueOf(entity.getOperation().getConvertingRate()));
        return temp;
    }

    public SyncProtocol.Entity toProtoEntity() {
        final SyncProtocol.Operation.Builder builder = SyncProtocol.Operation.newBuilder()
                .setDescription(getDescription())
                .setAmount(getAmount().toPlainString())
                .setTime(getTime().getTime())
                .setCategoryId(getCategory().getId().toString());
        if(getOrderer() != null)
            builder.setOrdererId(getOrderer().getId().toString());
        if(getBeneficiar() != null)
            builder.setBeneficiarId(getBeneficiar().getId().toString());
        if(getConvertingRate() != null)
            builder.setConvertingRate(getConvertingRate().doubleValue());

        SyncProtocol.Operation op = builder.build();

        return SyncProtocol.Entity.newBuilder()
                .setID(getId().toString())
                .setDeleted(isDeleted())
                //.setLastModified(getLastModified().getTime()) // don't send server time to itself
                .setOperation(op)
                .build();
    }

    /**
     * Calling this method means we have full operation object with all data built and ready for reverting
     * @param operation operation to be reverted
     */
    public static boolean revertOperation(final Operation operation) throws SQLException {
        final Account chargeAcc = operation.getOrderer();
        final Account benefAcc = operation.getBeneficiar();
        final BigDecimal amount = operation.getAmount();
        return  TransactionManager.callInTransaction(DbProvider.getHelper().getConnectionSource(),
                new Callable<Boolean>() {
                    public Boolean call() throws Exception {
                        EntityDao<Operation> opDao = DbProvider.getHelper().getDao(Operation.class);
                        if(opDao.delete(operation) == 0) {
                            throw new IllegalStateException();
                        }

                        switch (operation.getCategory().getType()) {
                            case TRANSFER:
                                benefAcc.setAmount(benefAcc.getAmount().subtract(operation.getAmountDelivered()));
                                chargeAcc.setAmount(chargeAcc.getAmount().add(amount));
                                if(DbProvider.getHelper().getAccountDao().update(benefAcc) == 0 || DbProvider.getHelper().getAccountDao().update(chargeAcc) == 0) {
                                    throw new IllegalStateException();
                                }
                                break;
                            case EXPENSE: // add subtracted value
                                chargeAcc.setAmount(chargeAcc.getAmount().add(amount));
                                if(DbProvider.getHelper().getAccountDao().update(chargeAcc) == 0) {
                                    throw new IllegalStateException();
                                }
                                break;
                            case INCOME: // subtract added value
                                benefAcc.setAmount(benefAcc.getAmount().subtract(amount));
                                if(DbProvider.getHelper().getAccountDao().update(benefAcc) == 0) {
                                    throw new IllegalStateException();
                                }
                                break;
                        }
                        return true;
                    }
                });
    }

    /**
     * Calling this method means we have full operation object with all data built and ready for applying
     * @param operation operation to be applied
     */
    public static boolean applyOperation(final Operation operation) throws SQLException {
        final Account chargeAcc = operation.getOrderer();
        final Account benefAcc = operation.getBeneficiar();
        final BigDecimal amount = operation.getAmount();

        return  TransactionManager.callInTransaction(DbProvider.getHelper().getConnectionSource(),
                new Callable<Boolean>() {
                    public Boolean call() throws Exception {
                        if(DbProvider.getHelper().getOperationDao().createOrUpdate(operation).getNumLinesChanged() == 0)
                            throw new IllegalStateException();

                        switch (operation.getCategory().getType()) {
                            case TRANSFER:
                                benefAcc.setAmount(benefAcc.getAmount().add(operation.getAmountDelivered()));
                                chargeAcc.setAmount(chargeAcc.getAmount().subtract(amount));
                                if(DbProvider.getHelper().getAccountDao().update(benefAcc) == 0 || DbProvider.getHelper().getAccountDao().update(chargeAcc) == 0) // apply to db
                                    throw new IllegalStateException();
                                break;
                            case EXPENSE: // subtract value
                                chargeAcc.setAmount(chargeAcc.getAmount().subtract(amount));
                                if(DbProvider.getHelper().getAccountDao().update(chargeAcc) == 0)
                                    throw new IllegalStateException();
                                break;
                            case INCOME: // add value
                                benefAcc.setAmount(benefAcc.getAmount().add(amount));
                                if(DbProvider.getHelper().getAccountDao().update(benefAcc) == 0)
                                    throw new IllegalStateException();
                                break;
                        }
                        return true;
                    }
                });
    }
}
