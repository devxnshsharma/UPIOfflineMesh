package com.demo.upimesh.dto;

/** Either field may be null (= leave unchanged). online: bridge has internet; active: device is in the mesh. */
public record DeviceUpdateRequest(Boolean online, Boolean active) {}
