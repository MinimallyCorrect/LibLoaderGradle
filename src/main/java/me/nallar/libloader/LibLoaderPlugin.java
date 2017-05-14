package me.nallar.libloader;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.val;
import me.nallar.libloader.LibLoader.Version;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.java.archives.Attributes;
import org.gradle.api.plugins.MavenPlugin;
import org.gradle.api.plugins.MavenPluginConvention;
import org.gradle.jvm.tasks.Jar;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class LibLoaderPlugin implements Plugin<Project> {
	private static final Set<String> excludedGroups = new HashSet<>(Arrays.asList(
		"org.ow2.asm",
		"jline",
		"net.minecraft",
		"org.scala-lang",
		"org.apache.logging.log4j"
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

	@SuppressWarnings("unchecked")
	@SneakyThrows
	@Override
	public void apply(Project project) {
		try (val ois = new ObjectInputStream(new FileInputStream(new File(project.getBuildDir(), "libloader-cache.obj")))) {
			cachedHashes = (Map<String, String>) ois.readObject();
		} catch (IOException ignored) {
		}
		project.getPlugins().apply("java");
		project.getPlugins().apply("maven");
		// conf2ScopeMappings.addMapping(1, configurations.myCustom, "compile")
		libLoaderConfig = project.getConfigurations().create("libLoader");
		project.getConfigurations().getByName("compile").extendsFrom(libLoaderConfig);

		val mavenConvention = (MavenPluginConvention) project.getConvention().getPlugins().get("maven");
		mavenConvention.getConf2ScopeMappings().addMapping(MavenPlugin.COMPILE_PRIORITY + 1, libLoaderConfig, "compile");

		project.getDependencies().add("compileOnly", "me.nallar.libloader:LibLoader:0.1-SNAPSHOT");
		project.getExtensions().add("libLoader", extension);

		project.afterEvaluate(this::afterEvaluate);
	}

	@SneakyThrows
	private void afterEvaluate(Project project) {
		val c = libLoaderConfig.getResolvedConfiguration();

		val time = System.currentTimeMillis();
		val jar = (Jar) project.getTasks().getByName("jar");
		val attr = jar.getManifest().getAttributes();

		Map<String, ArtifactVersion> alreadyLibLoaded = new HashMap<>();
		for (ResolvedArtifact resolvedArtifact : c.getResolvedArtifacts()) {
			val id = resolvedArtifact.getModuleVersion().getId();

			if (excludedGroups.contains(id.getGroup()))
				continue;

			try (val zis = new ZipInputStream(new FileInputStream(resolvedArtifact.getFile()))) {
				ZipEntry e;
				while ((e = zis.getNextEntry()) != null) {
					if (!e.getName().equals("META-INF/MANIFEST.MF"))
						continue;
					val manifest = new Manifest(zis);
					int j = 0;
					val main = manifest.getMainAttributes();
					String group;
					while ((group = main.getValue("LibLoader-group" + j)) != null) {
						val name = main.getValue("LibLoader-name" + j);
						val classifier = main.getValue("LibLoader-classifier" + j);
						val version = new Version(main.getValue("LibLoader-version" + j));
						String key = group + '.' + name + dash(classifier);
						val artifactVersion = new ArtifactVersion(group, name, classifier, version);
						val previous = alreadyLibLoaded.put(key, artifactVersion);
						if (previous != null && previous.version.compareTo(version) > 0)
							alreadyLibLoaded.put(key, previous);
						j++;
					}
				}
			}
		}

		int i = 0;
		for (ResolvedArtifact resolvedArtifact : c.getResolvedArtifacts()) {
			val id = resolvedArtifact.getModuleVersion().getId();

			if (excludedGroups.contains(id.getGroup()))
				continue;

			val currentVersion = new Version(id.getVersion());
			val key = id.getGroup() + '.' + id.getName() + dash(resolvedArtifact.getClassifier());
			val alreadyVersion = alreadyLibLoaded.get(key);
			if (alreadyVersion != null && alreadyVersion.version.compareTo(currentVersion) >= 0)
				continue;
			alreadyLibLoaded.remove(key);

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
		for (ArtifactVersion av : alreadyLibLoaded.values()) {
			if (extension.log)
				System.out.println("LibLoader noted parent dependency " + av.group + '.' + av.name + dash(av.classifier) + '-' + av.version);
			put(attr, "LibLoader-group" + i, av.group);
			put(attr, "LibLoader-name" + i, av.name);
			put(attr, "LibLoader-classifier" + i, av.classifier);
			put(attr, "LibLoader-version" + i, av.version.toString());
			i++;
		}

		try (val oos = new ObjectOutputStream(new FileOutputStream(new File(project.getBuildDir(), "libloader-cache.obj")))) {
			//noinspection unchecked
			oos.writeObject(cachedHashes);
		} catch (IOException ignored) {
		}

		if (extension.bundleLibLoader) {
			val compileOnly = project.getConfigurations().getByName("compileOnly").getResolvedConfiguration();
			for (ResolvedArtifact resolvedArtifact : compileOnly.getResolvedArtifacts()) {
				val id = resolvedArtifact.getModuleVersion().getId();
				if (id.getGroup().equals("me.nallar.libloader") && id.getName().equals("LibLoader")) {
					jar.from(project.zipTree(resolvedArtifact.getFile()));
				}
			}
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
		public boolean bundleLibLoader = true;
	}

	@AllArgsConstructor
	private static class ArtifactVersion {
		String group;
		String name;
		String classifier;
		Version version;
	}
}
