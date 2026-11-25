package cn.jia.agent.api;

import cn.jia.agent.service.TaskAssigneeAdmission;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Only the affected HTTP controllers expose this transient admission failure. */
interface TaskAssigneeAdmissionErrors {
    @ExceptionHandler(TaskAssigneeAdmission.Paused.class)
    default ResponseEntity<JsonResult<Void>> admissionPaused() {
        String code = "TASK_ASSIGNEE_ADMISSION_PAUSED";
        return ResponseEntity.status(503).header("Cache-Control", "private, no-store").body(JsonResult.failure(code, code));
    }
}
