package org.motechproject.mds.builder.impl;

import javassist.CtClass;
import javassist.NotFoundException;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.reflect.FieldUtils;
import org.motechproject.commons.date.model.Time;
import org.motechproject.mds.builder.EntityMetadataBuilder;
import org.motechproject.mds.domain.ClassData;
import org.motechproject.mds.domain.ComboboxHolder;
import org.motechproject.mds.domain.Entity;
import org.motechproject.mds.domain.EntityType;
import org.motechproject.mds.domain.Field;
import org.motechproject.mds.domain.FieldSetting;
import org.motechproject.mds.domain.RelationshipHolder;
import org.motechproject.mds.domain.Type;
import org.motechproject.mds.javassist.MotechClassPool;
import org.motechproject.mds.reflections.ReflectionsUtil;
import org.motechproject.mds.repository.AllEntities;
import org.motechproject.mds.util.ClassName;
import org.motechproject.mds.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.jdo.annotations.Element;
import javax.jdo.annotations.IdGeneratorStrategy;
import javax.jdo.annotations.IdentityType;
import javax.jdo.annotations.Join;
import javax.jdo.annotations.NullValue;
import javax.jdo.annotations.PersistenceModifier;
import javax.jdo.annotations.Persistent;
import javax.jdo.metadata.ClassMetadata;
import javax.jdo.metadata.ClassPersistenceModifier;
import javax.jdo.metadata.CollectionMetadata;
import javax.jdo.metadata.ColumnMetadata;
import javax.jdo.metadata.ElementMetadata;
import javax.jdo.metadata.FieldMetadata;
import javax.jdo.metadata.InheritanceMetadata;
import javax.jdo.metadata.JDOMetadata;
import javax.jdo.metadata.JoinMetadata;
import javax.jdo.metadata.MapMetadata;
import javax.jdo.metadata.MemberMetadata;
import javax.jdo.metadata.PackageMetadata;
import java.util.Map;

import static org.apache.commons.lang.StringUtils.defaultIfBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.motechproject.mds.util.Constants.MetadataKeys.DATABASE_COLUMN_NAME;
import static org.motechproject.mds.util.Constants.MetadataKeys.MAP_KEY_TYPE;
import static org.motechproject.mds.util.Constants.MetadataKeys.MAP_VALUE_TYPE;
import static org.motechproject.mds.util.Constants.Util.CREATION_DATE_FIELD_NAME;
import static org.motechproject.mds.util.Constants.Util.CREATOR_FIELD_NAME;
import static org.motechproject.mds.util.Constants.Util.DATANUCLEUS;
import static org.motechproject.mds.util.Constants.Util.ID_FIELD_NAME;
import static org.motechproject.mds.util.Constants.Util.MODIFICATION_DATE_FIELD_NAME;
import static org.motechproject.mds.util.Constants.Util.MODIFIED_BY_FIELD_NAME;
import static org.motechproject.mds.util.Constants.Util.OWNER_FIELD_NAME;
import static org.motechproject.mds.util.Constants.Util.TRUE;
import static org.motechproject.mds.util.Constants.Util.VALUE_GENERATOR;


/**
 * The <code>EntityMetadataBuilderImpl</code> class is responsible for building jdo metadata for an
 * entity class.
 */
@Component
public class EntityMetadataBuilderImpl implements EntityMetadataBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityMetadataBuilderImpl.class);

    private static final String[] FIELD_VALUE_GENERATOR = new String[]{
            CREATOR_FIELD_NAME, OWNER_FIELD_NAME, CREATION_DATE_FIELD_NAME,
            MODIFIED_BY_FIELD_NAME, MODIFICATION_DATE_FIELD_NAME
    };

    private AllEntities allEntities;

    @Override
    public void addEntityMetadata(JDOMetadata jdoMetadata, Entity entity, Class<?> definition) {
        String className = (entity.isDDE()) ? entity.getClassName() : ClassName.getEntityName(entity.getClassName());
        String packageName = ClassName.getPackage(className);
        String tableName = getTableName(entity.getClassName(), entity.getModule(), entity.getNamespace(), entity.getTableName(), null);

        PackageMetadata pmd = getPackageMetadata(jdoMetadata, packageName);
        ClassMetadata cmd = getClassMetadata(pmd, ClassName.getSimpleName(ClassName.getEntityName(entity.getClassName())));

        cmd.setTable(tableName);
        cmd.setDetachable(true);
        cmd.setIdentityType(IdentityType.APPLICATION);
        cmd.setPersistenceModifier(ClassPersistenceModifier.PERSISTENCE_CAPABLE);

        InheritanceMetadata imd = cmd.newInheritanceMetadata();
        imd.setCustomStrategy("complete-table");

        if (!entity.isSubClassOfMdsEntity()) {
            addIdField(cmd, entity);
        }

        addMetadataForFields(cmd, null, entity, EntityType.STANDARD, definition);
    }

    @Override
    public void addHelperClassMetadata(JDOMetadata jdoMetadata, ClassData classData, Entity entity,
                                       EntityType entityType, Class<?> definition) {
        String packageName = ClassName.getPackage(classData.getClassName());
        String simpleName = ClassName.getSimpleName(classData.getClassName());
        String tableName = getTableName(classData.getClassName(), classData.getModule(), classData.getNamespace(),
                entity == null ? "" : entity.getTableName(), entityType);

        PackageMetadata pmd = getPackageMetadata(jdoMetadata, packageName);
        ClassMetadata cmd = getClassMetadata(pmd, simpleName);

        cmd.setTable(tableName);
        cmd.setDetachable(true);
        cmd.setIdentityType(IdentityType.APPLICATION);
        cmd.setPersistenceModifier(ClassPersistenceModifier.PERSISTENCE_CAPABLE);

        InheritanceMetadata imd = cmd.newInheritanceMetadata();
        imd.setCustomStrategy("complete-table");

        addIdField(cmd, classData.getClassName());

        if (entity != null) {
            addMetadataForFields(cmd, classData, entity, entityType, definition);
        }
    }

    @Override
    @Transactional
    public void fixEnhancerIssuesInMetadata(JDOMetadata jdoMetadata) {
        for (PackageMetadata pmd : jdoMetadata.getPackages()) {
            for (ClassMetadata cmd : pmd.getClasses()) {
                String className = String.format("%s.%s", pmd.getName(), cmd.getName());
                String trimmedClassName = ClassName.trimTrashHistorySuffix(className);
                Entity entity = allEntities.retrieveByClassName(trimmedClassName);
                EntityType entityType = EntityType.forClassName(className);

                if (null != entity) {
                    for (MemberMetadata mmd : cmd.getMembers()) {
                        CollectionMetadata collMd = mmd.getCollectionMetadata();
                        Field field = entity.getField(mmd.getName());

                        if (null != field && field.getType().isRelationship()) {
                            fixRelationMetadata(mmd, collMd, field, entityType);
                        }

                        if (null != collMd) {
                            fixCollectionMetadata(collMd);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void addBaseMetadata(JDOMetadata jdoMetadata, ClassData classData, EntityType entityType, Class<?> definition) {
        addHelperClassMetadata(jdoMetadata, classData, null, entityType, definition);
    }

    private void fixCollectionMetadata(CollectionMetadata collMd) {
        String elementType = collMd.getElementType();
        String trimmedElementType = ClassName.trimTrashHistorySuffix(elementType);

        if (null != MotechClassPool.getEnhancedClassData(trimmedElementType)) {
            collMd.setEmbeddedElement(false);
        }
    }

    private void fixRelationMetadata(MemberMetadata mmd, CollectionMetadata collMd, Field field, EntityType entityType) {
        RelationshipHolder holder = new RelationshipHolder(field);

        if ((holder.isOneToMany() || holder.isManyToMany()) && null != collMd) {
            collMd.setDependentElement(holder.isCascadeDelete() || entityType == EntityType.TRASH);
        } else if (holder.isOneToOne()) {
            mmd.setDependent(holder.isCascadeDelete());
        }
    }

    private void addDefaultFetchGroupMetadata(FieldMetadata fmd, Class<?> definition) {
        java.lang.reflect.Field field = FieldUtils.getField(definition, fmd.getName(), true);

        if (field == null) {
            LOGGER.warn("Unable to retrieve field {} from class {}. Putting the field in the default fetch group by default.",
                    fmd.getName(), definition.getName());
            fmd.setDefaultFetchGroup(true);
        } else {
            Persistent persistentAnnotation = ReflectionsUtil.getAnnotationSelfOrAccessor(field, Persistent.class);

            // set to true, unless there is a JDO annotation that specifies otherwise
            if (persistentAnnotation == null || StringUtils.isBlank(persistentAnnotation.defaultFetchGroup())) {
                fmd.setDefaultFetchGroup(true);
            }
        }
    }

    private void addMetadataForFields(ClassMetadata cmd, ClassData classData, Entity entity, EntityType entityType,
                                      Class<?> definition) {
        for (Field field : entity.getFields()) {
            // Metadata for ID field has been added earlier in addIdField() method
            if (!field.getName().equals(ID_FIELD_NAME)) {
                FieldMetadata fmd = null;

                if (checkIfFieldIsNotInherited(field.getName(), entity)) {
                    fmd = setFieldMetadata(cmd, classData, entity, entityType, field, definition);
                }
                // when field is in Lookup, we set field metadata indexed to retrieve instance faster
                if (!field.getLookups().isEmpty() && entityType.equals(EntityType.STANDARD)) {
                    if (fmd == null) {
                        String inheritedFieldName = ClassName.getSimpleName(entity.getSuperClass()) + "." + field.getName();
                        fmd = cmd.newFieldMetadata(inheritedFieldName);
                    }
                    fmd.setIndexed(true);
                }
                if (fmd != null) {
                    setColumnParameters(fmd, field);
                    // Check whether the field is required and set appropriate metadata
                    fmd.setNullValue(field.isRequired() ? NullValue.EXCEPTION : NullValue.NONE);
                }
            }
        }
    }

    private boolean checkIfFieldIsNotInherited(String fieldName, Entity entity) {
        if (entity.isSubClassOfMdsEntity() && (ArrayUtils.contains(FIELD_VALUE_GENERATOR, fieldName))) {
            return false;
        } else {
            // return false if it is inherited field from superclass
            return entity.isBaseEntity() || !isFieldFromSuperClass(entity.getSuperClass(), fieldName);
        }
    }

    private boolean isFieldFromSuperClass(String className, String fieldName) {
        Entity entity = allEntities.retrieveByClassName(className);
        return entity.getField(fieldName) != null;
    }

    private FieldMetadata setFieldMetadata(ClassMetadata cmd, ClassData classData, Entity entity,
                                           EntityType entityType, Field field, Class<?> definition) {
        String name = field.getName();

        Type type = field.getType();
        Class<?> typeClass = type.getTypeClass();

        if (ArrayUtils.contains(FIELD_VALUE_GENERATOR, name)) {
            return setAutoGenerationMetadata(cmd, name);
        } else if (type.isCombobox()) {
            return setComboboxMetadata(cmd, entity, field, definition);
        } else if (type.isRelationship()) {
            return setRelationshipMetadata(cmd, classData, field, entityType, definition);
        } else if (Map.class.isAssignableFrom(typeClass)) {
            return setMapMetadata(cmd, field, definition);
        } else if (Time.class.isAssignableFrom(typeClass)) {
            return setTimeMetadata(cmd, name);
        }
        return cmd.newFieldMetadata(name);
    }

    private void setColumnParameters(FieldMetadata fmd, Field field) {
        if ((field.getMetadata(DATABASE_COLUMN_NAME) != null || field.getSettingByName(Constants.Settings.STRING_MAX_LENGTH) != null
                || field.getSettingByName(Constants.Settings.STRING_TEXT_AREA) != null)) {
            FieldSetting maxLengthSetting = field.getSettingByName(Constants.Settings.STRING_MAX_LENGTH);

            ColumnMetadata colMd = fmd.newColumnMetadata();
            // only set the metadata if the setting is different from default
            if (maxLengthSetting != null && !StringUtils.equals(maxLengthSetting.getValue(),
                    maxLengthSetting.getDetails().getDefaultValue())) {
                colMd.setLength(Integer.parseInt(maxLengthSetting.getValue()));
            }

            // if TextArea then change length
            if (field.getSettingByName(Constants.Settings.STRING_TEXT_AREA) != null &&
                    "true".equalsIgnoreCase(field.getSettingByName(Constants.Settings.STRING_TEXT_AREA).getValue())) {
                fmd.setIndexed(false);
                colMd.setSQLType("CLOB");
            }
            if (field.getMetadata(DATABASE_COLUMN_NAME) != null) {
                colMd.setName(field.getMetadata(DATABASE_COLUMN_NAME).getValue());
            }
        }
    }

    private FieldMetadata setTimeMetadata(ClassMetadata cmd, String name) {
        // for time we register our converter which persists as string
        FieldMetadata fmd = cmd.newFieldMetadata(name);

        fmd.setPersistenceModifier(PersistenceModifier.PERSISTENT);
        fmd.setDefaultFetchGroup(true);
        fmd.newExtensionMetadata(DATANUCLEUS, "type-converter-name", "dn.time-string");
        return fmd;
    }

    private FieldMetadata setMapMetadata(ClassMetadata cmd, Field field, Class<?> definition) {
        FieldMetadata fmd = cmd.newFieldMetadata(field.getName());

        org.motechproject.mds.domain.FieldMetadata keyMetadata = field.getMetadata(MAP_KEY_TYPE);
        org.motechproject.mds.domain.FieldMetadata valueMetadata = field.getMetadata(MAP_VALUE_TYPE);
        boolean serialized = keyMetadata != null && valueMetadata != null &&
                (!keyMetadata.getValue().equals(String.class.getName()) || !valueMetadata.getValue().equals(String.class.getName()));

        // Depending on the types of key and value of the map we either serialize the map or create a separate table for it
        fmd.setSerialized(serialized);

        addDefaultFetchGroupMetadata(fmd, definition);

        MapMetadata mmd = fmd.newMapMetadata();

        if (serialized) {
            mmd.setSerializedKey(true);
            mmd.setSerializedValue(true);
        } else {
            mmd.setKeyType(String.class.getName());
            mmd.setValueType(String.class.getName());

            fmd.setTable(getTableName(cmd.getTable(), field.getName()));
            fmd.newJoinMetadata();
        }
        return fmd;
    }

    private FieldMetadata setRelationshipMetadata(ClassMetadata cmd, ClassData classData, Field field,
                                         EntityType entityType, Class<?> definition) {
        RelationshipHolder holder = new RelationshipHolder(classData, field);

        FieldMetadata fmd = cmd.newFieldMetadata(field.getName());

        addDefaultFetchGroupMetadata(fmd, definition);

        //For standard classes, we always set persist and update cascades to true
        fmd.newExtensionMetadata(DATANUCLEUS, "cascade-persist",
                entityType != EntityType.STANDARD ? Boolean.toString(holder.isCascadePersist()) : TRUE);
        fmd.newExtensionMetadata(DATANUCLEUS, "cascade-update",
                entityType != EntityType.STANDARD ? Boolean.toString(holder.isCascadeUpdate()) : TRUE);

        processRelationship(fmd, holder, field, definition, entityType);

        return fmd;
    }

    private void processRelationship(FieldMetadata fmd, RelationshipHolder holder, Field field, Class<?> definition, EntityType entityType) {
        String relatedClass = holder.getRelatedClass();

        if (holder.isOneToMany() || holder.isManyToMany()) {
            CollectionMetadata colMd = getOrCreateCollectionMetadata(fmd);
            colMd.setElementType(relatedClass);
            colMd.setEmbeddedElement(false);
            colMd.setSerializedElement(false);
            colMd.setDependentElement(holder.isCascadeDelete() || entityType == EntityType.TRASH);
        } else if (holder.isOneToOne()) {
            fmd.setPersistenceModifier(PersistenceModifier.PERSISTENT);
            fmd.setDependent(holder.isCascadeDelete() || entityType == EntityType.TRASH);
        }

        if (holder.isManyToMany()) {
            addManyToManyMetadata(fmd, holder, field, definition, entityType);
        }

        if (entityType == EntityType.TRASH || entityType == EntityType.HISTORY) {
            addMappedByToHistoryTrashMetadata(fmd, field, definition);
        }
    }

    private void addManyToManyMetadata(FieldMetadata fmd, RelationshipHolder holder, Field field, Class<?> definition, EntityType entityType) {
        java.lang.reflect.Field fieldDefinition = FieldUtils.getDeclaredField(definition, field.getName(), true);
        Join join = fieldDefinition.getAnnotation(Join.class);

        JoinMetadata jmd = null;
        // Join metadata must be present at both sides of the M:N relation in Datanucleus 3.2
        if (join == null || entityType != EntityType.STANDARD) {
            jmd = fmd.newJoinMetadata();
            jmd.setOuter(false);
        }

        // If tables and column names have been specified in annotations, do not set their metadata
        if (!holder.isOwningSide()) {
            Persistent persistent = fieldDefinition.getAnnotation(Persistent.class);
            Element element = fieldDefinition.getAnnotation(Element.class);

            setTableNameMetadata(fmd, persistent, field, holder, entityType);
            setElementMetadata(fmd, element, holder, entityType);

            if (join != null && StringUtils.isNotEmpty(join.column()) && entityType != EntityType.STANDARD) {
                setJoinMetadata(jmd, fmd, join.column());
            } else if (join == null || StringUtils.isEmpty(join.column())) {
                setJoinMetadata(jmd, fmd, ClassName.getSimpleName(field.getEntity().getClassName()) + "_ID".toUpperCase());
            }
        }
    }

    private void setElementMetadata(FieldMetadata fmd, Element element, RelationshipHolder holder, EntityType entityType) {
        if (element != null && StringUtils.isNotEmpty(element.column()) && entityType != EntityType.STANDARD) {
            ElementMetadata emd = fmd.newElementMetadata();
            emd.setColumn(element.column());
        } else if (element == null || StringUtils.isEmpty(element.column())) {
            ElementMetadata emd = fmd.newElementMetadata();
            emd.setColumn(ClassName.getSimpleName(ClassName.trimTrashHistorySuffix(holder.getRelatedClass()) + "_ID").toUpperCase());
        }
    }

    private void setJoinMetadata(JoinMetadata jmd, FieldMetadata fmd, String column) {
        JoinMetadata joinMetadata;
        if (jmd == null) {
            joinMetadata = fmd.newJoinMetadata();
            joinMetadata.setOuter(false);
        } else {
            joinMetadata = jmd;
        }

        joinMetadata.setColumn(column);
    }

    private void setTableNameMetadata(FieldMetadata fmd, Persistent persistent, Field field, RelationshipHolder holder, EntityType entityType) {
        if (persistent != null && StringUtils.isNotEmpty(persistent.table()) && entityType != EntityType.STANDARD) {
            fmd.setTable(entityType.getTableName(persistent.table()));
        } else if (persistent == null || StringUtils.isEmpty(persistent.table())) {
            fmd.setTable(getJoinTableName(field.getEntity().getModule(), field.getEntity().getNamespace(), field.getName(), holder.getRelatedField()));
        }
    }

    private void addMappedByToHistoryTrashMetadata(FieldMetadata fmd, Field field, Class<?> definition) {
        Persistent annotation = FieldUtils.getDeclaredField(definition, field.getName(), true).getAnnotation(Persistent.class);
        if (annotation != null) {
            String mappedBy = annotation.mappedBy();

            if (StringUtils.isNotEmpty(mappedBy)) {
                fmd.setMappedBy(mappedBy);
            }
        }
    }

    private FieldMetadata setComboboxMetadata(ClassMetadata cmd, Entity entity, Field field, Class<?> definition) {
        ComboboxHolder holder = new ComboboxHolder(entity, field);
        FieldMetadata fmd = cmd.newFieldMetadata(field.getName());

        if (holder.isStringList() || holder.isEnumList()) {
            addDefaultFetchGroupMetadata(fmd, definition);
            fmd.setTable(getTableName(cmd.getTable(), field.getName()));

            JoinMetadata jm = fmd.newJoinMetadata();
            jm.setColumn(field.getName() + "_OID");
        }
        return fmd;
    }

    private FieldMetadata setAutoGenerationMetadata(ClassMetadata cmd, String name) {
        FieldMetadata fmd = cmd.newFieldMetadata(name);
        fmd.setPersistenceModifier(PersistenceModifier.PERSISTENT);
        fmd.setDefaultFetchGroup(true);
        fmd.newExtensionMetadata(DATANUCLEUS, VALUE_GENERATOR, "ovg." + name);
        return fmd;
    }

    private static ClassMetadata getClassMetadata(PackageMetadata pmd, String className) {
        ClassMetadata[] classes = pmd.getClasses();
        if (ArrayUtils.isNotEmpty(classes)) {
            for (ClassMetadata cmd : classes) {
                if (StringUtils.equals(className, cmd.getName())) {
                    return cmd;
                }
            }
        }
        return pmd.newClassMetadata(className);
    }

    private static PackageMetadata getPackageMetadata(JDOMetadata jdoMetadata, String packageName) {
        // first look for existing metadata
        PackageMetadata[] packages = jdoMetadata.getPackages();
        if (ArrayUtils.isNotEmpty(packages)) {
            for (PackageMetadata pkgMetadata : packages) {
                if (StringUtils.equals(pkgMetadata.getName(), packageName)) {
                    return pkgMetadata;
                }
            }
        }
        // if not found, create new
        return jdoMetadata.newPackageMetadata(packageName);
    }

    private static String getTableName(String table, String suffix) {
        String tableName = table;

        if (isNotBlank(suffix)) {
            tableName += "_" + suffix;
        }

        return tableName.replace('-', '_').replace(' ', '_').toUpperCase();
    }

    public static String getTableName(Entity entity, EntityType type) {
        String tableName = getTableName(entity.getClassName(), entity.getModule(), entity.getNamespace(), entity.getTableName(), type);
        if (type == EntityType.STANDARD) {
            return tableName;
        }
        return getTableName(tableName, "_" + type.toString());
    }

    public static String getTableName(String className, String module, String namespace, String tableName, EntityType entityType) {
        String simpleName = ClassName.getSimpleName(className);
        String mod = defaultIfBlank(module, "MDS");
        String table = defaultIfBlank(tableName, "");

        StringBuilder builder = new StringBuilder();
        if (table.isEmpty()) {
            builder.append(mod).append("_");

            if (isNotBlank(namespace)) {
                builder.append(namespace).append("_");
            }

            builder.append(simpleName);

            return builder.toString().replace('-', '_').replace(' ', '_').toUpperCase();
        } else {
            builder.append(table);

            if (entityType != null && !EntityType.STANDARD.equals(entityType)) {
                builder.append("__").append(entityType.toString());
            }

            return builder.toString();
        }
    }

    private void addIdField(ClassMetadata cmd, Entity entity) {
        boolean containsID = null != entity.getField(ID_FIELD_NAME);
        boolean isBaseClass = entity.isBaseEntity();

        if (containsID && isBaseClass) {
            FieldMetadata metadata = cmd.newFieldMetadata(ID_FIELD_NAME);
            metadata.setValueStrategy(IdGeneratorStrategy.INCREMENT);
            metadata.setPrimaryKey(true);
            metadata.setIndexed(true);
        }
    }

    private void addIdField(ClassMetadata cmd, String className) {
        boolean containsID;
        boolean isBaseClass;

        try {
            CtClass ctClass = MotechClassPool.getDefault().getOrNull(className);
            containsID = null != ctClass && null != ctClass.getField(ID_FIELD_NAME);
            isBaseClass = null != ctClass && (null == ctClass.getSuperclass() || Object.class.getName().equalsIgnoreCase(ctClass.getSuperclass().getName()));
        } catch (NotFoundException e) {
            containsID = false;
            isBaseClass = false;
        }

        if (containsID && isBaseClass) {
            FieldMetadata metadata = cmd.newFieldMetadata(ID_FIELD_NAME);
            metadata.setValueStrategy(IdGeneratorStrategy.INCREMENT);
            metadata.setPrimaryKey(true);
            metadata.setIndexed(true);
        }
    }

    private CollectionMetadata getOrCreateCollectionMetadata(FieldMetadata fmd) {
        CollectionMetadata collMd = fmd.getCollectionMetadata();
        if (collMd == null) {
            collMd = fmd.newCollectionMetadata();
        }
        return collMd;
    }

    private String getJoinTableName(String module, String namespace, String owningSideName, String inversedSideNameWithSuffix) {
        String mod = defaultIfBlank(module, "MDS");

        StringBuilder builder = new StringBuilder();
        builder.append(mod).append("_");

        if (isNotBlank(namespace)) {
            builder.append(namespace).append("_");
        }

        builder.append("Join_").
                append(ClassName.trimTrashHistorySuffix(inversedSideNameWithSuffix)).append("_").
                append(owningSideName).
                append(ClassName.getEntityTypeSuffix(inversedSideNameWithSuffix));

        return builder.toString().replace('-', '_').replace(' ', '_').toUpperCase();
    }

    @Autowired
    public void setAllEntities(AllEntities allEntities) {
        this.allEntities = allEntities;
    }
}
