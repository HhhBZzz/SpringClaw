package com.springclaw.runtime.memory.port;

import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.ProjectMemoryItem;

import java.util.List;

public interface ProjectMemorySource {

    List<ProjectMemoryItem> read(MemoryScope scope);
}
