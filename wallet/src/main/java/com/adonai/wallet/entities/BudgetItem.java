package com.adonai.wallet.entities;

import com.adonai.wallet.DatabaseDAO;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Created by adonai on 19.06.14.
 */
@DatabaseTable(tableName = "budget_item")
public class BudgetItem {

    public BudgetItem(Budget parentBudget) {
        this.parentBudget = parentBudget;
    }

    @DatabaseField(id = true)
    private UUID id = UUID.randomUUID();

    @DatabaseField(canBeNull = false, foreign = true)
    private Budget parentBudget;

    @DatabaseField(canBeNull = false, foreign = true)
    private Category category;

    @DatabaseField
    private BigDecimal maxAmount;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Budget getParentBudget() {
        return parentBudget;
    }

    public void setParentBudget(Budget parentBudget) {
        this.parentBudget = parentBudget;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public void setMaxAmount(BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
    }

    public BigDecimal getProgress() {
        final BigDecimal result = BigDecimal.ZERO;
        if(parentBudget == null) // parent budget is not set, nothing to count
            return result;

        return DatabaseDAO.getInstance().getAmountForBudget(parentBudget, category);
    }
}
