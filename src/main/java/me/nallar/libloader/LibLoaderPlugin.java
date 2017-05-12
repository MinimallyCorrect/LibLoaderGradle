package me.nallar.libloader;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.val;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.java.archives.Attributes;
import org.gradle.jvm.tasks.Jar;

import java.io.*;
import java.nio.file.*;
import java.security.*;

public class LibLoaderPlugin implements Plugin<Project> {
	private LibLoaderGradleExtension extension = new LibLoaderGradleExtension();
	private Configuration libLoaderConfig;

	private static String dash(String s) {
		if (s == null || s.isEmpty())
			return "";
		return '-' + s;
	}

	@SneakyThrows
	private static String sha512(File f) {
		val digest = MessageDigest.getInstance("SHA-512");
		byte[] hash = digest.digest(Files.readAllBytes(f.toPath()));

		val hexString = new StringBuilder();
		//noinspection ForLoopReplaceableByForEach
		for (int i = 0; i < hash.length; i++) {
			String hex = Integer.toHexString(0xff & hash[i]);
			if (hex.length() == 1) hexString.append('0');
			hexString.append(hex);
		}

		return hexString.toString();
	}

	private static void put(Attributes attr, String key, String value) {
		if (value != null && !value.isEmpty()) {
			attr.put(key, value);
		}
	}

	@SneakyThrows
	@Override
	public void apply(Project project) {
		project.getPlugins().apply("java");
		libLoaderConfig = project.getConfigurations().create("libLoader");
		project.getConfigurations().getByName("compileOnly").extendsFrom(libLoaderConfig);
		project.getConfigurations().getByName("testCompile").extendsFrom(libLoaderConfig);
		project.getRepositories().add(project.getRepositories().maven(it -> it.setUrl("https://repo.nallar.me/")));
		project.getDependencies().add("compileOnly", "me.nallar.libloader:LibLoader:0.1-SNAPSHOT");
		project.getExtensions().add("libLoader", extension);

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
				val id = resolvedArtifact.getModuleVersion().getId();
				put(attr, "LibLoader-group" + i, id.getGroup());
				put(attr, "LibLoader-name" + i, id.getName());
				put(attr, "LibLoader-classifier" + i, resolvedArtifact.getClassifier());
				put(attr, "LibLoader-version" + i, id.getVersion());
				val hash = sha512(resolvedArtifact.getFile());
				put(attr, "LibLoader-sha512hash" + i, hash);

				boolean urlWorks = false;
				if (!extension.bundleDependencies && !id.getVersion().toLowerCase().endsWith("-snapshot")) {
					val url = "https://jcenter.bintray.com/" + id.getGroup().replace('.', '/') + '/'
						+ id.getName() + dash(id.getVersion()) + '/' + id.getName() + dash(id.getVersion())
						+ dash(resolvedArtifact.getClassifier()) + '.' + resolvedArtifact.getExtension();

					put(attr, "LibLoader-url" + i, url);
					urlWorks = true;
					throw new UnsupportedOperationException();
				}

				if (!urlWorks) {
					put(attr, "LibLoader-file" + i, resolvedArtifact.getFile().getName());
				}

				// TODO: resolve URLs somehow? Gradle API doesn't let us do this currently
				// put(attr, "LibLoader-url" + i, resolvedArtifact.);
				put(attr, "LibLoader-buildTime" + i, String.valueOf(time));
				task.from(resolvedArtifact.getFile());
				i++;
			}
		}
	}

	@Data
	public static class LibLoaderGradleExtension {
		public boolean bundleDependencies = true;
	}
}
