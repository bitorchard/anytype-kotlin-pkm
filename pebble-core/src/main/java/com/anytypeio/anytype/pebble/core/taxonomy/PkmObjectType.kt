package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.core_models.ObjectType

/**
 * Sealed hierarchy representing the 17 PKM object types.
 *
 * Tier 1 — Built-in types ([isBuiltIn] = true): reuse existing AnyType types;
 * no API call needed.
 *
 * Tier 2 — Custom types ([isBuiltIn] = false): have `ot-pkm-*` unique keys and
 * must be created by [SchemaBootstrapper].
 */
sealed class PkmObjectType(
    val uniqueKey: String,
    val displayName: String,
    val layout: ObjectType.Layout,
    val isBuiltIn: Boolean,
    val requiredRelations: List<PkmRelation>,
    val optionalRelations: List<PkmRelation>
) {

    // ── Tier 1: Built-in AnyType types ────────────────────────────────────────

    object PersonType : PkmObjectType(
        uniqueKey = "ot-human",
        displayName = "Person",
        layout = ObjectType.Layout.PROFILE,
        isBuiltIn = true,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(
            PkmRelation.Email, PkmRelation.Phone, PkmRelation.BelongsTo, PkmRelation.Tag
        )
    )

    object TaskType : PkmObjectType(
        uniqueKey = "ot-task",
        displayName = "Task",
        layout = ObjectType.Layout.TODO,
        isBuiltIn = true,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(
            PkmRelation.Assignee, PkmRelation.DueDate, PkmRelation.Done,
            PkmRelation.Priority, PkmRelation.Context
        )
    )

    object NoteType : PkmObjectType(
        uniqueKey = "ot-note",
        displayName = "Note",
        layout = ObjectType.Layout.NOTE,
        isBuiltIn = true,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(PkmRelation.Description, PkmRelation.Tag, PkmRelation.Source)
    )

    object ProjectType : PkmObjectType(
        uniqueKey = "ot-project",
        displayName = "Project",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = true,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(
            PkmRelation.Description, PkmRelation.Status, PkmRelation.DueDate, PkmRelation.Assignee
        )
    )

    object BookmarkType : PkmObjectType(
        uniqueKey = "ot-bookmark",
        displayName = "Bookmark",
        layout = ObjectType.Layout.BOOKMARK,
        isBuiltIn = true,
        requiredRelations = listOf(PkmRelation.Name, PkmRelation.Source),
        optionalRelations = listOf(PkmRelation.Description, PkmRelation.Tag)
    )

    // ── Tier 2: Custom PKM types (ot-pkm-* prefix) ────────────────────────────

    object EventType : PkmObjectType(
        uniqueKey = "ot-pkm-event",
        displayName = "Event",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name, PkmRelation.Date),
        optionalRelations = listOf(
            PkmRelation.StartDate, PkmRelation.EndDate, PkmRelation.LocatedAt,
            PkmRelation.Attendees, PkmRelation.Description
        )
    )

    object ReminderType : PkmObjectType(
        uniqueKey = "ot-pkm-reminder",
        displayName = "Reminder",
        layout = ObjectType.Layout.TODO,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name, PkmRelation.DueDate),
        optionalRelations = listOf(PkmRelation.RelatedTo, PkmRelation.Done, PkmRelation.Context)
    )

    object PlaceType : PkmObjectType(
        uniqueKey = "ot-pkm-place",
        displayName = "Place",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(PkmRelation.Description, PkmRelation.RelatedTo)
    )

    object OrganizationType : PkmObjectType(
        uniqueKey = "ot-pkm-org",
        displayName = "Organization",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(PkmRelation.Description, PkmRelation.Url, PkmRelation.RelatedTo)
    )

    object TopicType : PkmObjectType(
        uniqueKey = "ot-pkm-topic",
        displayName = "Topic",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(PkmRelation.Description, PkmRelation.Area, PkmRelation.Tag)
    )

    object MeetingType : PkmObjectType(
        uniqueKey = "ot-pkm-meeting",
        displayName = "Meeting",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name, PkmRelation.Date),
        optionalRelations = listOf(
            PkmRelation.Attendees, PkmRelation.Description, PkmRelation.RelatedTo
        )
    )

    object VoiceInputType : PkmObjectType(
        uniqueKey = "ot-pkm-voice-input",
        displayName = "Voice Input",
        layout = ObjectType.Layout.NOTE,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name, PkmRelation.RawText),
        optionalRelations = listOf(PkmRelation.CreatedDate, PkmRelation.Status, PkmRelation.ChangeSetId)
    )

    object TimeEntryType : PkmObjectType(
        uniqueKey = "ot-pkm-time-entry",
        displayName = "Time Entry",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Date, PkmRelation.Duration),
        optionalRelations = listOf(
            PkmRelation.Person, PkmRelation.Activity, PkmRelation.StartDate, PkmRelation.EndDate,
            PkmRelation.RelatedTo, PkmRelation.RawText
        )
    )

    object AssetType : PkmObjectType(
        uniqueKey = "ot-pkm-asset",
        displayName = "Asset",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(PkmRelation.Mileage, PkmRelation.RelatedTo, PkmRelation.Description)
    )

    object MaintenanceRecordType : PkmObjectType(
        uniqueKey = "ot-pkm-maintenance-record",
        displayName = "Maintenance Record",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name, PkmRelation.Date),
        optionalRelations = listOf(
            PkmRelation.Asset, PkmRelation.Mileage, PkmRelation.Cost,
            PkmRelation.Description, PkmRelation.RawText
        )
    )

    object ExpenseType : PkmObjectType(
        uniqueKey = "ot-pkm-expense",
        displayName = "Expense",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Cost, PkmRelation.Date),
        optionalRelations = listOf(
            PkmRelation.Category, PkmRelation.Merchant, PkmRelation.RelatedTo, PkmRelation.RawText
        )
    )

    object HealthMetricType : PkmObjectType(
        uniqueKey = "ot-pkm-health-metric",
        displayName = "Health Metric",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Metric, PkmRelation.Value, PkmRelation.Date),
        optionalRelations = listOf(PkmRelation.Unit, PkmRelation.Person, PkmRelation.RawText)
    )

    object MediaItemType : PkmObjectType(
        uniqueKey = "ot-pkm-media-item",
        displayName = "Media Item",
        layout = ObjectType.Layout.BASIC,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name, PkmRelation.MediaType),
        optionalRelations = listOf(
            PkmRelation.MediaStatus, PkmRelation.Rating, PkmRelation.Person,
            PkmRelation.Area, PkmRelation.Description
        )
    )

    object DecisionType : PkmObjectType(
        uniqueKey = "ot-pkm-decision",
        displayName = "Decision",
        layout = ObjectType.Layout.NOTE,
        isBuiltIn = false,
        requiredRelations = listOf(PkmRelation.Name),
        optionalRelations = listOf(
            PkmRelation.Rationale, PkmRelation.Alternatives, PkmRelation.Date,
            PkmRelation.RelatedTo, PkmRelation.RawText
        )
    )

    companion object {
        /** All 19 PKM object types (5 built-in + 14 custom). */
        fun all(): List<PkmObjectType> = listOf(
            PersonType, TaskType, NoteType, ProjectType, BookmarkType,
            EventType, ReminderType, PlaceType, OrganizationType, TopicType,
            MeetingType, VoiceInputType, TimeEntryType, AssetType,
            MaintenanceRecordType, ExpenseType, HealthMetricType, MediaItemType, DecisionType
        )

        /** 5 built-in types that reuse existing AnyType type keys. */
        fun builtIn(): List<PkmObjectType> = all().filter { it.isBuiltIn }

        /** 14 custom types with `ot-pkm-*` keys that must be created by [SchemaBootstrapper]. */
        fun custom(): List<PkmObjectType> = all().filter { !it.isBuiltIn }

        /** Look up a type by its unique key. Returns null if not found. */
        fun byKey(key: String): PkmObjectType? = all().firstOrNull { it.uniqueKey == key }
    }
}
