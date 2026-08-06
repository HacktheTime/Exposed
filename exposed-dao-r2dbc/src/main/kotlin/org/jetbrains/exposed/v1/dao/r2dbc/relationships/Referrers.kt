package org.jetbrains.exposed.v1.dao.r2dbc.relationships

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.dao.r2dbc.Entity
import org.jetbrains.exposed.v1.dao.r2dbc.EntityClass
import org.jetbrains.exposed.v1.dao.r2dbc.ExperimentalR2dbcDaoApi
import org.jetbrains.exposed.v1.dao.r2dbc.entityCache
import org.jetbrains.exposed.v1.r2dbc.SizedIterable
import org.jetbrains.exposed.v1.r2dbc.emptySized
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import kotlin.reflect.KProperty

/**
 * Class responsible for implementing property delegates of the read-only properties involved in a one-to-many
 * relation, which retrieves all child entities that reference the parent entity.
 *
 * R2DBC counterpart of JDBC's `Referrers` from `References.kt`. The property delegate returns a
 * [SizedIterable] backed by a [DeferredQuery] — iteration is suspended until terminal operations
 * (`collect`, `count`, etc.) are invoked.
 *
 * @param reference The reference column defined on the child entity's associated table.
 * @param factory The [EntityClass] associated with the child entity that references the parent entity.
 * @param cache Whether loaded reference entities should be stored in the [org.jetbrains.exposed.v1.dao.r2dbc.EntityCache].
 */
@ExperimentalR2dbcDaoApi
class Referrers<ParentID : Any, in Parent : Entity<ParentID>, ChildID : Any, out Child : Entity<ChildID>, REF>(
    val reference: Column<REF>,
    val factory: EntityClass<ChildID, Child>,
    val cache: Boolean,
    references: Map<Column<*>, Column<*>>? = null
) {
    /** The set of columns and their [SortOrder] for ordering referred entities in one-to-many relationship. */
    private val orderByExpressions = linkedSetOf<Pair<Expression<*>, SortOrder>>()

    /** Returns the order by expressions as an array. */
    internal fun getOrderByExpressions(): Array<Pair<Expression<*>, SortOrder>> = orderByExpressions.toTypedArray()

    /**
     * Full child→parent column mapping for the relationship. Single-column references derive this from
     * `reference.referee` lazily; composite-FK references pass the full map explicitly. Mirrors JDBC's
     * `Referrers.allReferences`.
     */
    val allReferences: Map<Column<*>, Column<*>> = references ?: run {
        reference.referee ?: error("Column $reference is not a reference")
        if (factory.table != reference.table) {
            error("Column $reference and factory ${factory.table.tableName} point to different tables")
        }
        @Suppress("UNCHECKED_CAST")
        mapOf(reference as Column<*> to reference.referee!!)
    }

    @Suppress("UNCHECKED_CAST")
    operator fun getValue(thisRef: Parent, property: KProperty<*>): SizedIterable<Child> {
        return DeferredQuery(base = {
            doQuery(
                reference,
                factory as EntityClass<ChildID, Entity<ChildID>>,
                cache,
                allReferences,
                getOrderByExpressions(),
                thisRef as Entity<ParentID>
            ) as SizedIterable<Child>
        })
    }

    /** Modifies this reference to sort entities based on multiple columns as specified in [order]. */
    infix fun orderBy(order: List<Pair<Expression<*>, SortOrder>>) = this.also {
        orderByExpressions.addAll(order)
    }

    /** Modifies this reference to sort entities according to the specified [order]. */
    infix fun orderBy(order: Pair<Expression<*>, SortOrder>) = orderBy(listOf(order))

    /** Modifies this reference to sort entities by a column specified in [expression] using ascending order. */
    infix fun orderBy(expression: Expression<*>) = orderBy(listOf(expression to SortOrder.ASC))

    /** Modifies this reference to sort entities based on multiple columns as specified in [order]. */
    fun orderBy(vararg order: Pair<Expression<*>, SortOrder>) = orderBy(order.asList())
}

@Suppress("UNCHECKED_CAST", "NestedBlockDepth", "SpreadOperator", "LongParameterList")
private suspend fun <ChildID : Any, Child : Entity<ChildID>, REF> doQuery(
    reference: Column<REF>,
    factory: EntityClass<ChildID, Child>,
    cache: Boolean,
    allReferences: Map<Column<*>, Column<*>>,
    orderByExpressions: Array<Pair<Expression<*>, SortOrder>>,
    entity: Entity<*>
): SizedIterable<Child> {
    val transaction = TransactionManager.currentOrNull()

    return if (transaction == null) {
        if (entity.id._value == null) {
            emptySized()
        } else if (entity.hasInReferenceCache(reference)) {
            val cached = entity.getReferenceFromCache<Any?>(reference)
            when (cached) {
                is SizedIterable<*> -> cached as SizedIterable<Child>
                null -> emptySized()
                else -> error("Cached referrer has unexpected type: ${cached::class}")
            }
        } else {
            error(
                "Referring entities for $reference are not in the entity cache. Reading a referrers " +
                    "relation without a transaction in context requires " +
                    "DatabaseConfig.keepLoadedReferencesOutOfTransaction to be enabled, and the relation " +
                    "to have been loaded earlier inside a transaction."
            )
        }
    } else {
        if (entity.id._value == null) {
            transaction.entityCache.flush()
        }

        val isComposite = allReferences.size > 1 || allReferences.values.firstOrNull()?.table is CompositeIdTable
        val query: suspend () -> SizedIterable<Child> = if (!isComposite) {
            val referee = reference.referee!!
            val refereeValue = with(entity) { referee.lookup() }

            val needsEntityIdUnwrap = reference.columnType !is EntityIDColumnType<*> &&
                referee.columnType is EntityIDColumnType<*> && refereeValue is EntityID<*>

            val refValue = if (needsEntityIdUnwrap) refereeValue.value as REF else refereeValue as REF
            ; {
                factory.find { reference eq refValue }
                    .orderBy(*orderByExpressions)
            }
        } else {
            val parentValuesByChildColumn = allReferences.map { (childColumn, parentColumn) ->
                val parentValueRaw = with(entity) { (parentColumn as Column<Any?>).lookup() }
                val parentValue = if (parentValueRaw is EntityID<*> && childColumn.columnType !is EntityIDColumnType<*>) {
                    parentValueRaw._value
                } else {
                    parentValueRaw
                }
                childColumn to parentValue
            }

            ; {
                factory.find {
                    parentValuesByChildColumn.map { (childColumn, value) ->
                        (childColumn as Column<Any?>) eq value
                    }.reduce { acc, next -> acc and next }
                }.orderBy(*orderByExpressions)
            }
        }

        val result = if (cache) {
            transaction.entityCache.getOrPutReferrers(entity.id, reference, query)
        } else {
            query()
        }

        entity.storeReferenceInCache(reference, result)
        result
    }
}

/**
 * A foreign key reference column that carries the [entityClass] of the parent entity it points to.
 * Extends [Column] as a higher-type so its property delegate can resolve the referenced entity directly.
 */
open class ReferencedColumn<ID : Any, EntityType : Entity<ID>, EntityClassType : EntityClass<ID, EntityType>>(
    table: Table,
    name: String,
    columnType: IColumnType<EntityID<ID>>,
    val entityClass: EntityClassType
) : Column<EntityID<ID>>(table, name, columnType)

/**
 * Nullable variant of [ReferencedColumn] produced by `reference(...).nullable()`. Keeps the same schema (the
 * underlying [columnType] is marked nullable) but its property delegate resolves to an [OptionalAccessor], so
 * reading the parent yields [null] when unset and writing `null` clears the link.
 */
class OptionalReferencedColumn<ID : Any, EntityType : Entity<ID>, EntityClassType : EntityClass<ID, EntityType>>(
    table: Table,
    name: String,
    columnType: IColumnType<EntityID<ID>>,
    entityClass: EntityClassType
) : ReferencedColumn<ID, EntityType, EntityClassType>(table, name, columnType, entityClass)

/**
 * Marks this [ReferencedColumn] as nullable while keeping its reference-resolving type (and [entityClass]),
 * so it can still be used as an entity reference property delegate — unlike the plain [Column.nullable], which
 * would degrade it to a regular nullable [Column] and lose the parent-entity resolution.
 *
 * This is only available in a `Table` DDL-building scope (like other column modifiers), because the referenced
 * table must receive the new nullable column instance.
 */
context(table: Table)
fun <ID : Any, EntityType : Entity<ID>, EntityClassType : EntityClass<ID, EntityType>> ReferencedColumn<ID, EntityType, EntityClassType>.nullable(): OptionalReferencedColumn<ID, EntityType, EntityClassType> {
    val newColumn = OptionalReferencedColumn(table, name, columnType, entityClass)
    newColumn.foreignKey = foreignKey
    newColumn.defaultValueFun = defaultValueFun
    newColumn.columnType.nullable = true
    return table.replaceColumn(this, newColumn)
}


/**
 * Creates a foreign key reference column whose value is stored as an [EntityID] of [refColumn] and which,
 * when used as an entity property delegate, resolves the referenced parent [ref] entity lazily — the
 * R2DBC counterpart of combining a DSL `reference` with `referencedOn`.
 *
 * @param name Name of the reference column.
 * @param refColumn The column from the parent table which will be referenced.
 * @param ref The [EntityClass] of the parent entity.
 * @param onDelete Optional [ReferenceOption] for a deleted parent row.
 * @param onUpdate Optional [ReferenceOption] for a changed referenced column value.
 * @param fkName Optional foreign key constraint name.
 */
context(table: Table)
fun <REF : Any, EntityType : Entity<REF>, EntityClassType : EntityClass<REF, EntityType>> reference(
    name: String,
    refColumn: Column<EntityID<REF>>,
    ref: EntityClassType,
    onDelete: ReferenceOption? = null,
    onUpdate: ReferenceOption? = null,
    fkName: String? = null
): ReferencedColumn<REF, EntityType, EntityClassType> {
    val innerIdColumn = (refColumn.columnType as EntityIDColumnType<REF>).idColumn
    val idColumn = table.entityId(name, innerIdColumn)
    val referencedColumn = ReferencedColumn<REF, EntityType, EntityClassType>(
        table = table,
        name = name,
        columnType = idColumn.columnType,
        entityClass = ref
    ).also { column ->
        with(table) { column.references(refColumn, onDelete, onUpdate, fkName) }
        table.replaceColumn(idColumn, column)
    }
    return referencedColumn
}

/**
 * Checks if this referenced column points to the same row as [entity] (compares against `entity.id`).
 * Applies both to nullable and non-nullable reference columns and works inside a query builder
 * ([org.jetbrains.exposed.v1.core.SqlExpressionBuilder]) scope, e.g. `EntityClass.find { Owners.bb eq someOwner }`.
 */
infix fun <ID : Any, EntityType : Entity<ID>, EntityClassType : EntityClass<ID, EntityType>> ReferencedColumn<ID, EntityType, EntityClassType>.eq(entity: EntityType): Op<Boolean> =
    this eq entity.id

/**
 * Checks if this referenced column does not point to the given [entity].
 */
infix fun <ID : Any, EntityType : Entity<ID>, EntityClassType : EntityClass<ID, EntityType>> ReferencedColumn<ID, EntityType, EntityClassType>.neq(
    entity: EntityType
): Op<Boolean> = this neq entity.id

/**
 * Checks if this referenced column points to any of the given [entities].
 */
infix fun <ID : Any, EntityType : Entity<ID>, EntityClassType : EntityClass<ID, EntityType>> ReferencedColumn<ID, EntityType, EntityClassType>.inList(
    entities: Iterable<EntityType>
): Op<Boolean> = this inList entities.map { it.id }

/**
 * Checks if this referenced column points to none of the given [entities].
 */
infix fun <ID : Any, EntityType : Entity<ID>, EntityClassType : EntityClass<ID, EntityType>> ReferencedColumn<ID, EntityType, EntityClassType>.notInList(
    entities: Iterable<EntityType>
): Op<Boolean> = this notInList entities.map { it.id }
