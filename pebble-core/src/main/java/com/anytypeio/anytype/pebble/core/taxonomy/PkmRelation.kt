package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.core_models.Relation

/**
 * Sealed hierarchy representing all relations used in the PKM taxonomy.
 *
 * Built-in relations are referenced by their AnyType key (e.g. "name", "done") and
 * do not need to be created via [com.anytypeio.anytype.pebble.core.PebbleGraphService].
 *
 * Custom relations use the `pkm-` prefix and must be created by [SchemaBootstrapper].
 */
sealed class PkmRelation(
    val key: String,
    val displayName: String,
    val format: Relation.Format,
    val isBuiltIn: Boolean
) {

    // ── Built-in relations (no creation needed) ────────────────────────────────

    object Name : PkmRelation("name", "Name", Relation.Format.SHORT_TEXT, isBuiltIn = true)
    object Description : PkmRelation("description", "Description", Relation.Format.LONG_TEXT, isBuiltIn = true)
    object Tag : PkmRelation("tag", "Tag", Relation.Format.TAG, isBuiltIn = true)
    object Status : PkmRelation("status", "Status", Relation.Format.STATUS, isBuiltIn = true)
    object Assignee : PkmRelation("assignee", "Assignee", Relation.Format.OBJECT, isBuiltIn = true)
    object DueDate : PkmRelation("dueDate", "Due Date", Relation.Format.DATE, isBuiltIn = true)
    object Done : PkmRelation("done", "Done", Relation.Format.CHECKBOX, isBuiltIn = true)
    object Source : PkmRelation("source", "Source", Relation.Format.URL, isBuiltIn = true)
    object Phone : PkmRelation("phone", "Phone", Relation.Format.PHONE, isBuiltIn = true)
    object Email : PkmRelation("email", "Email", Relation.Format.EMAIL, isBuiltIn = true)
    object Url : PkmRelation("url", "URL", Relation.Format.URL, isBuiltIn = true)
    object Priority : PkmRelation("priority", "Priority", Relation.Format.STATUS, isBuiltIn = true)
    object CreatedDate : PkmRelation("createdDate", "Created Date", Relation.Format.DATE, isBuiltIn = true)

    // ── Custom relations (pkm-* prefix, must be created via createRelation) ─────

    object ParticipatesIn : PkmRelation("pkm-participatesIn", "Participates In", Relation.Format.OBJECT, isBuiltIn = false)
    object LocatedAt : PkmRelation("pkm-locatedAt", "Located At", Relation.Format.OBJECT, isBuiltIn = false)
    object BelongsTo : PkmRelation("pkm-belongsTo", "Belongs To", Relation.Format.OBJECT, isBuiltIn = false)
    object Attendees : PkmRelation("pkm-attendees", "Attendees", Relation.Format.OBJECT, isBuiltIn = false)
    object RelatedTo : PkmRelation("pkm-relatedTo", "Related To", Relation.Format.OBJECT, isBuiltIn = false)
    object Context : PkmRelation("pkm-context", "Context", Relation.Format.TAG, isBuiltIn = false)
    object Area : PkmRelation("pkm-area", "Area", Relation.Format.OBJECT, isBuiltIn = false)
    object StartDate : PkmRelation("pkm-startDate", "Start Date", Relation.Format.DATE, isBuiltIn = false)
    object EndDate : PkmRelation("pkm-endDate", "End Date", Relation.Format.DATE, isBuiltIn = false)
    object RawText : PkmRelation("pkm-rawText", "Raw Text", Relation.Format.LONG_TEXT, isBuiltIn = false)
    object Person : PkmRelation("pkm-person", "Person", Relation.Format.OBJECT, isBuiltIn = false)
    object Activity : PkmRelation("pkm-activity", "Activity", Relation.Format.TAG, isBuiltIn = false)
    object Date : PkmRelation("pkm-date", "Date", Relation.Format.DATE, isBuiltIn = false)
    object Duration : PkmRelation("pkm-duration", "Duration", Relation.Format.NUMBER, isBuiltIn = false)
    object List : PkmRelation("pkm-list", "List", Relation.Format.OBJECT, isBuiltIn = false)
    object Quantity : PkmRelation("pkm-quantity", "Quantity", Relation.Format.SHORT_TEXT, isBuiltIn = false)
    object Asset : PkmRelation("pkm-asset", "Asset", Relation.Format.OBJECT, isBuiltIn = false)
    object Mileage : PkmRelation("pkm-mileage", "Mileage", Relation.Format.NUMBER, isBuiltIn = false)
    object Cost : PkmRelation("pkm-cost", "Cost", Relation.Format.NUMBER, isBuiltIn = false)
    object Category : PkmRelation("pkm-category", "Category", Relation.Format.TAG, isBuiltIn = false)
    object Merchant : PkmRelation("pkm-merchant", "Merchant", Relation.Format.SHORT_TEXT, isBuiltIn = false)
    object Metric : PkmRelation("pkm-metric", "Metric", Relation.Format.TAG, isBuiltIn = false)
    object Value : PkmRelation("pkm-value", "Value", Relation.Format.NUMBER, isBuiltIn = false)
    object Unit : PkmRelation("pkm-unit", "Unit", Relation.Format.SHORT_TEXT, isBuiltIn = false)
    object MediaType : PkmRelation("pkm-mediaType", "Media Type", Relation.Format.TAG, isBuiltIn = false)
    object MediaStatus : PkmRelation("pkm-mediaStatus", "Media Status", Relation.Format.STATUS, isBuiltIn = false)
    object Rating : PkmRelation("pkm-rating", "Rating", Relation.Format.NUMBER, isBuiltIn = false)
    object Rationale : PkmRelation("pkm-rationale", "Rationale", Relation.Format.LONG_TEXT, isBuiltIn = false)
    object Alternatives : PkmRelation("pkm-alternatives", "Alternatives", Relation.Format.LONG_TEXT, isBuiltIn = false)
    /** Links a VoiceInput to its corresponding ChangeSet for audit-trail queries. */
    object ChangeSetId : PkmRelation("pkm-changeSetId", "Change Set ID", Relation.Format.SHORT_TEXT, isBuiltIn = false)

    companion object {
        /** All 13 built-in relations referenced by the PKM taxonomy. */
        fun builtIn(): kotlin.collections.List<PkmRelation> = listOf(
            Name, Description, Tag, Status, Assignee, DueDate, Done, Source, Phone, Email, Url, Priority, CreatedDate
        )

        /** All 30 custom relations (pkm-* prefix) that must be created in a fresh space. */
        fun custom(): kotlin.collections.List<PkmRelation> = listOf(
            ParticipatesIn, LocatedAt, BelongsTo, Attendees, RelatedTo, Context, Area, StartDate, EndDate,
            RawText, Person, Activity, Date, Duration, List, Quantity, Asset, Mileage, Cost, Category,
            Merchant, Metric, Value, Unit, MediaType, MediaStatus, Rating, Rationale, Alternatives, ChangeSetId
        )

        /** All relations (built-in + custom). */
        fun all(): kotlin.collections.List<PkmRelation> = builtIn() + custom()

        /** Relations that represent links between objects (OBJECT format). */
        fun objectRelations(): kotlin.collections.List<PkmRelation> =
            all().filter { it.format == Relation.Format.OBJECT }
    }
}
