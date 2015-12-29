package com.paic.hyperion.core.hotfix

import org.gradle.api.Project

/**
 * shamelessly stolen from nuwa
 */
class HotFixExtension {
    HashSet<String> includePackage = []
    HashSet<String> excludeClass = []
    boolean debugOn = true

    HotFixExtension(Project project) {
    }
}
