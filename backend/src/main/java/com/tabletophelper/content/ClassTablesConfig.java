package com.tabletophelper.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.content.internal.ClassTablesFile;
import com.tabletophelper.rules.character.ClassTables;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The class tables the character rules read (D151, D169): {@code content} reads {@code data/content/class-tables.json}
 * into {@link ClassTables} once, as the application starts, and gives it to the modules that use it, such as
 * {@code character}. Unlike the content loaders, this doesn't depend on {@code content_versions} or
 * {@code content.mode}: the file is read at every start, a maintenance start included, and a file that can't be read
 * stops the start. The classes loader lists the file too, so a change to it loads the classes again, whose slot
 * progressions and subclass levels it writes.
 */
@Configuration
@Slf4j
public class ClassTablesConfig {

    @Bean
    public ClassTables classTables(ObjectMapper objectMapper) {
        ClassTables tables = ClassTablesFile.read(objectMapper);
        log.info("Read the class tables of {} classes and {} subclasses from {}", tables.classes().size(),
                tables.subclasses().size(), ClassTablesFile.PATH);
        return tables;
    }
}
