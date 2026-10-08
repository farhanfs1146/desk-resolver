package com.forward.desk_resolver.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApplicationResponse {

    private Long id;
    private String appName;
    private String moduleName;
    private boolean active;
}
