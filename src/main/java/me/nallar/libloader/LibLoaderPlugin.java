package me.nallar.libloader;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.val;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.jvm.tasks.Jar;

public class LibLoaderPlugin implements Plugin<Project> {
	private LibLoaderGradleExtension extension = new LibLoaderGradleExtension();
	private Configuration libLoaderConfig;

	@SneakyThrows
	@Override
	public void apply(Project project) {
		libLoaderConfig = project.getConfigurations().create("libLoader");
		val compileOnly = project.getConfigurations().create("compileOnly");
		compileOnly.extendsFrom(libLoaderConfig);
		project.getRepositories().add(project.getRepositories().maven(it -> it.setUrl("https://repo.nallar.me/")));
		project.getDependencies().add("compileOnly", "me.nallar.libloader:LibLoader:0.1-SNAPSHOT");
		project.getExtensions().add("modpatcher", extension);

		project.afterEvaluate(this::afterEvaluate);
	}

	private void afterEvaluate(Project project) {
		val c = libLoaderConfig.getResolvedConfiguration();

		val time = System.currentTimeMillis();
		val tasks = project.getTasks().withType(Jar.class);
		for (Jar task : tasks) {
			val attr = task.getManifest().getAttributes();
			int i = 0;
			for (ResolvedArtifact resolvedArtifact : c.getResolvedArtifacts()) {
				attr.put("LibLoader-group" + i, resolvedArtifact.getModuleVersion().getId().getGroup());
				attr.put("LibLoader-name" + i, resolvedArtifact.getModuleVersion().getId().getName());
				attr.put("LibLoader-artifact" + i, resolvedArtifact.getClassifier());
				attr.put("LibLoader-version" + i, resolvedArtifact.getModuleVersion().getId().getVersion());
				attr.put("LibLoader-file" + i, resolvedArtifact.getFile().getName());
				// TODO: resolve URLs somehow? Gradle API doesn't let us do this currently
				// attr.put("LibLoader-url" + i, resolvedArtifact.);
				attr.put("LibLoader-buildTime" + i, String.valueOf(time));
				task.from(resolvedArtifact.getFile());
				i++;
			}
		}
	}

	@Data
	public static class LibLoaderGradleExtension {
		public boolean bundleDependencies = true;
		public boolean bundleSnapshotDependencies = true;
	}
}
