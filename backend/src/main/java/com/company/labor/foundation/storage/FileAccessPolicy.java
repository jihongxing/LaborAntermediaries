package com.company.labor.foundation.storage;

import com.company.labor.foundation.context.ExecutionContext;

public interface FileAccessPolicy {
    boolean mayRead(ExecutionContext.Identity identity, PrivateFileStorage.Metadata file);
}
