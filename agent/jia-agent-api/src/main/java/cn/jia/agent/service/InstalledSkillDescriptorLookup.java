package cn.jia.agent.service;

import java.util.List;
import java.util.Objects;

/** Authoritative installation facts; NOT task authorization or execution admission. */
public interface InstalledSkillDescriptorLookup {
    /** manifestJson is verified normalized metadata, not a Provider config or an executable command. */
    record Descriptor(String skillKey, String skillVersion, String installationId,
            String packageSha256, String manifestSha256, String manifestJson,
            String runtimeInstallationId, String runtimeHostId, String reasonCode, boolean domainApi) {
        public Descriptor(String key,String version,String id,String pkg,String manifest,String json,String runtime,String host,String reason) {
            this(key,version,id,pkg,manifest,json,runtime,host,reason,false);
        }
        public String instructionPolicy() {return domainApi ? SkillExecutionService.NATIVE_DOMAIN_API_POLICY : SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY;}
        public boolean verified() { return reasonCode == null && manifestJson != null; }
        /** Native execution also accepts a verified instruction package without a manifest. */
        public boolean verifiedNative() { return reasonCode == null && packageSha256 != null
                && ((manifestJson == null && manifestSha256 == null) || verified() && manifestSha256 != null); }
        /** Installation evidence only; the caller still checks the current turn and business authority. */
        public boolean isInstructionOn(String runtimeId, String hostId) {
            return runtimeId != null && hostId != null && runtimeId.equals(runtimeInstallationId)
                    && hostId.equals(runtimeHostId) && verifiedNative() && manifestSha256 == null;
        }
    }
    record Snapshot(List<Descriptor> skills) {
        public Snapshot { skills = List.copyOf(Objects.requireNonNull(skills)); }
    }
    /** Includes historical installations needed for exact task bindings and recovery. */
    Snapshot read(SkillCapabilityLookup.Scope scope);

    /** Keep immutable descriptions prepared outside locks alive for one synchronous operation.
     * Implementations must still re-read authorization/installation facts inside action. */
    default <T> T withPreparedRead(SkillCapabilityLookup.Scope scope, java.util.function.Supplier<T> action) {
        read(scope);
        return action.get();
    }

    /** Current installations for profile display and new task selection, not task authorization. */
    default Snapshot readCurrent(SkillCapabilityLookup.Scope scope) { return read(scope); }

    /** Product discovery names keyed by installation ID. Display only; never part of a task
     * binding, package digest, authorization or readiness comparison. */
    default java.util.Map<String,String> displayTitles(SkillCapabilityLookup.Scope scope) { return java.util.Map.of(); }

    final class SourceUnavailable extends RuntimeException {
        public enum Reason { UNAVAILABLE, FORBIDDEN }
        private final Reason reason;
        public SourceUnavailable() { this(Reason.UNAVAILABLE,null); }
        public SourceUnavailable(Throwable cause) { this(Reason.UNAVAILABLE,cause); }
        public SourceUnavailable(Reason reason,Throwable cause) {
            super("SKILL_DESCRIPTOR_SOURCE_UNAVAILABLE",cause);
            this.reason=Objects.requireNonNull(reason);
        }
        /** Internal classification; no change to the public descriptor/error envelope. */
        public Reason reason() { return reason; }
    }
}
