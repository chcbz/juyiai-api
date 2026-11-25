package cn.jia.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Process-start policy only. Not a cluster drain, execution fence or wallet switch. */
@Component
public record TaskAssigneeAdmission(boolean paused) {
    public TaskAssigneeAdmission(@Value("${jia.agent.task-assignee.pause-new-admission:false}") boolean paused) {
        this.paused = paused;
    }
    public void requireOpen() { if (paused) throw new Paused(); }
    public static final class Paused extends RuntimeException {}
}
