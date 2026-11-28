package cn.jia.chat.service;

/** The single exact rendering of an immutable requirement used by admission and execution. */
final class ChatBountyRequirementContent {
    private ChatBountyRequirementContent() { }

    /** Private structured facts: preserve quantities, units, dependencies and exact source references.
     * Do not append protocol fields to the visible USER message or infer values from its prose. */
    static java.util.Map<String,Object> scopeFacts(cn.jia.agent.requirement.ConfirmedScope scope) {
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("schemaVersion",scope.schemaVersion());
        result.put("requirementRevision",Long.toString(scope.requirementRevision()));
        result.put("requirementSha256",scope.requirementSha256());
        result.put("scopeSha256",scope.scopeSha256());
        result.put("inputSnapshotDigest",scope.inputSnapshotDigest());
        result.put("acceptanceText",scope.acceptanceText());
        result.put("workItems",scope.workItems().stream().map(item->java.util.Map.<String,Object>of(
                "itemId",item.itemId(),"capabilityId",item.capabilityId(),"capabilityVersion",item.capabilityVersion(),
                "parameters",scalars(item.parameters()),"deliverables",scalars(item.deliverables()),
                "inputRefs",item.inputRefs(),"dependsOn",item.dependsOn())).toList());
        return java.util.Map.copyOf(result);
    }
    private static java.util.Map<String,Object> scalars(java.util.Map<String,cn.jia.agent.requirement.ConfirmedScope.Scalar> values) {
        var result=new java.util.LinkedHashMap<String,Object>();
        values.forEach((key,value)->result.put(key,java.util.Map.of("type",value.type().name(),"unit",value.unit(),"value",value.value())));
        return java.util.Map.copyOf(result);
    }

    static String render(String title, String description) {
        if (title == null || title.isBlank()) {
            throw new IllegalStateException("Exact requirement title is unavailable");
        }
        // Quick intake may store the same demand twice. Never normalize either field or
        // deduplicate paragraphs within a field: only exact whole-field equality is special.
        return title + (description == null || description.isEmpty() || description.equals(title)
                ? "" : "\n\n" + description);
    }
}
