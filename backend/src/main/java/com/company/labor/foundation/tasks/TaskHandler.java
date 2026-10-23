package com.company.labor.foundation.tasks;

public interface TaskHandler {
    String type();

    void execute(long taskId, String payload) throws Exception;
}
