package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;

/** Explicit native file boundary; stored output/manifest facts remain unchanged. */
public final class NativeOutputWire {
    private NativeOutputWire() { }
    static long byteLength(String value) {
        if (value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid byteLength");
        return Long.parseLong(value);
    }
    public record StagedOutputResponse(String outputId,String sha256,String byteLength,String state) {
        static StagedOutputResponse from(PersonalWorkspaceExecutionService.StagedOutput value) {
            return new StagedOutputResponse(value.outputId(),value.sha256(),Long.toString(value.byteLength()),value.state());
        }
    }
}
