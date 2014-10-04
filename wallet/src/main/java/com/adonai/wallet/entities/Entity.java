package com.adonai.wallet.entities;

import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

/**
 * Created by adonai on 24.09.14.
 */
public class Entity implements Serializable {

    @DatabaseField(columnName = "_id", generatedId = true)
    private UUID id;

    @DatabaseField(columnName = "last_modified", canBeNull = true, dataType = DataType.DATE_LONG)
    private Date lastModified;

    @DatabaseField
    private boolean deleted;

    @DatabaseField(dataType = DataType.SERIALIZABLE)
    private Entity backup; // indicates the synced entity is changed locally or not

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Date getLastModified() {
        return lastModified;
    }

    public void setLastModified(Date lastModified) {
        this.lastModified = lastModified;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isDirty() {
        return backup != null;
    }

    public Entity getBackup() {
        return backup;
    }

    public void setBackup(Entity backup) {
        this.backup = backup;
    }
}
