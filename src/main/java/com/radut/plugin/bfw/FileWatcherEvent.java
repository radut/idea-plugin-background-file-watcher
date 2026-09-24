package com.radut.plugin.bfw;

import java.time.LocalDateTime;

public record FileWatcherEvent(LocalDateTime time, String kind, boolean triggersActions, String reason, String path) {
}
