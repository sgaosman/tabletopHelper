package com.tabletophelper.content.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** The content a loader last loaded (V25, D169): the hash of its files and code version, and when. */
@Entity
@Table(name = "content_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ContentVersion {

    @Id
    @Column(length = 64)
    private String loader;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    @Column(name = "loader_version", nullable = false)
    private Integer loaderVersion;

    // The rows the data gave, in every table the loader writes
    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "loaded_at", nullable = false)
    private Instant loadedAt;
}
