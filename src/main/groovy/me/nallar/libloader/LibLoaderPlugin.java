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
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class LibLoaderPlugin implements Plugin<Project> {
	private static final Set<String> excludedGroups = new HashSet<>(Arrays.asList(
		"org.ow2.asm",
		"jline",
		"net.minecraft",
		"org.scala-lang"
	));
	private Map<String, String> cachedHashes = new HashMap<>();
	private LibLoaderGradleExtension extension = new LibLoaderGradleExtension();
	private Configuration libLoaderConfig;

	private static String dash(String s) {
		if (s == null || s.isEmpty())
			return "";
		return '-' + s;
	}

	@SneakyThrows
	private static String sha512(byte[] bytes) {
		val digest = MessageDigest.getInstance("SHA-512");
		byte[] hash = digest.digest(bytes);

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
		try (val ois = new ObjectInputStream(new FileInputStream(new File(project.getBuildDir(), "libloader-cache.obj")))) {
			//noinspection unchecked
			cachedHashes = (Map<String, String>) ois.readObject();
		} catch (IOException ignored) {
		}
		project.getPlugins().apply("java");
		project.getPlugins().apply("maven");
		libLoaderConfig = project.getConfigurations().create("libLoader");
		project.getConfigurations().getByName("compile").extendsFrom(libLoaderConfig);

		project.getRepositories().add(project.getRepositories().maven(it -> it.setUrl("https://repo.nallar.me/")));
		project.getDependencies().add("compileOnly", "me.nallar.libloader:LibLoader:0.1-SNAPSHOT");
		project.getExtensions().add("libLoader", extension);

		project.afterEvaluate(this::afterEvaluate);
	}

	@SneakyThrows
	private void afterEvaluate(Project project) {
		PomFixer.fix(project, libLoaderConfig);
		val c = libLoaderConfig.getResolvedConfiguration();

		val time = System.currentTimeMillis();
		val jar = (Jar) project.getTasks().getByName("jar");
		val attr = jar.getManifest().getAttributes();
		int i = 0;
		for (ResolvedArtifact resolvedArtifact : c.getResolvedArtifacts()) {
			val id = resolvedArtifact.getModuleVersion().getId();

			if (excludedGroups.contains(id.getGroup()))
				continue;

			put(attr, "LibLoader-group" + i, id.getGroup());
			put(attr, "LibLoader-name" + i, id.getName());
			put(attr, "LibLoader-classifier" + i, resolvedArtifact.getClassifier());
			put(attr, "LibLoader-version" + i, id.getVersion());
			val hash = sha512(Files.readAllBytes(resolvedArtifact.getFile().toPath()));
			put(attr, "LibLoader-sha512hash" + i, hash);

			String url = null;
			if (!extension.bundleDependencies && !id.getVersion().toLowerCase().endsWith("-snapshot")) {
				url = "https://jcenter.bintray.com/" + id.getGroup().replace('.', '/') + '/'
					+ id.getName() + '/' + id.getVersion() + '/' + id.getName() + dash(id.getVersion())
					+ dash(resolvedArtifact.getClassifier()) + '.' + resolvedArtifact.getExtension();
				val urlHash = hashFromUrl(url);
				if (!hash.equals(urlHash))
					url = null;
			}

			if (url != null) {
				put(attr, "LibLoader-url" + i, url);
			} else {
				put(attr, "LibLoader-file" + i, resolvedArtifact.getFile().getName());
				jar.from(resolvedArtifact.getFile());
			}

			if (extension.log)
				System.out.println("LibLoader included " + id.getGroup() + '.' + id.getName() + ". "
					+ ((url == null) ? "Bundled in jar." : "URL set to " + url));

			put(attr, "LibLoader-buildTime" + i, String.valueOf(time));
			i++;
		}
		try (val oos = new ObjectOutputStream(new FileOutputStream(new File(project.getBuildDir(), "libloader-cache.obj")))) {
			//noinspection unchecked
			oos.writeObject(cachedHashes);
		} catch (IOException ignored) {
		}
	}

	@SneakyThrows
	private String hashFromUrl(String url) {
		val cached = cachedHashes.get(url);
		if (cached != null)
			return cached;

		byte[] bytes = downloadUrl(new URL(url));
		String hash = "fail";
		if (bytes != null) {
			hash = sha512(bytes);
		}
		cachedHashes.put(url, hash);
		return hash;
	}

	private byte[] downloadUrl(URL toDownload) {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

		try (val is = toDownload.openStream()) {
			byte[] chunk = new byte[4096];
			int bytesRead;

			while ((bytesRead = is.read(chunk)) > 0) {
				outputStream.write(chunk, 0, bytesRead);
			}
		} catch (Exception e) {
			return null;
		}

		return outputStream.toByteArray();
	}

	@Data
	public static class LibLoaderGradleExtension {
		public boolean bundleDependencies = false;
		public boolean log = true;
	}
}
