package com.thinklab.kit.data;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.Index;
import io.micronaut.data.annotation.Indexes;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.util.UUID;

/** A minimal entity declaring indexes the way the services do, plus an explicitly mapped field name. */
@MappedEntity("indexed_widget")
@Indexes({
        @Index(columns = {"tenantId", "status"}),
        @Index(columns = {"code"}, unique = true, name = "uq_widget_code"),
        @Index(columns = {"externalRef"})
})
public record IndexedWidget(@Id UUID id, String tenantId, String status, String code,
                            @MappedProperty("ext_ref") String externalRef) {
}
