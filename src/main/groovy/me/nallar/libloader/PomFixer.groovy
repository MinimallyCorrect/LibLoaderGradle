package me.nallar.libloader

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

class PomFixer {
	static void fix(Project project, Configuration conf) {
		try {
			project.install.repositories.mavenInstaller.pom*.whenConfigured { pom ->
				pom.dependencies.removeAll {
					it.scope == 'test' || conf.dependencies.find({
						d -> d.getGroup() == it.groupId && d.getName() == it.artifactId
					})
				}
			}
		} catch (MissingPropertyException ignored) {
		}

		try {
			project.uploadArchives.repositories.mavenDeployer.pom*.whenConfigured { pom ->
				pom.dependencies.removeAll {
					it.scope == 'test' || conf.dependencies.find({
						d -> d.getGroup() == it.groupId && d.getName() == it.artifactId
					})
				}
			}
		} catch (MissingPropertyException ignored) {
		}
	}
}
