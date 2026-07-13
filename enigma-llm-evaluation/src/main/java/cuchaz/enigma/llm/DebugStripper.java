package cuchaz.enigma.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Strips debug and source metadata from every class in a jar, rewriting it in place.
 *
 * <p>A shipped obfuscator removes this metadata; leaving it in the benchmark corpus would hand the
 * model the answer for free. Concretely, a release jar run through tiny-remapper still carries:
 * <ul>
 *   <li>{@code LocalVariableTable} / {@code LocalVariableTypeTable} — the <em>original</em> parameter
 *       and local names ({@code dateStyle}, {@code timeStyle}), which {@link LlmProjectIndex} reads
 *       and {@link LlmPromptBuilder} injects into the prompt as {@code params};</li>
 *   <li>{@code MethodParameters} — a second carrier of the original parameter names;</li>
 *   <li>{@code SourceFile} / {@code SourceDebugExtension} — the original class name verbatim
 *       ({@code PreJava9DateFormatProvider.java} sitting inside {@code obf/C106}).</li>
 * </ul>
 * All of these are dropped here so the obfuscated jar leaks nothing but the (opaque) structure the
 * model is actually meant to reason about.
 */
final class DebugStripper {
	private DebugStripper() {
	}

	/** Rewrites {@code jar} in place with debug/source metadata removed from every class entry. */
	static void strip(Path jar) throws IOException {
		Path temp = jar.resolveSibling(jar.getFileName() + ".stripped");

		try (ZipFile zip = new ZipFile(jar.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				if (entry.isDirectory()) {
					continue;
				}

				byte[] bytes = readAll(zip, entry);

				if (entry.getName().endsWith(".class")) {
					bytes = stripClass(bytes);
				}

				ZipEntry copy = new ZipEntry(entry.getName());
				copy.setTime(entry.getTime());
				out.putNextEntry(copy);
				out.write(bytes);
				out.closeEntry();
			}
		}

		Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
	}

	private static byte[] stripClass(byte[] bytes) {
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(0);
		// SKIP_DEBUG drops LocalVariableTable, LocalVariableTypeTable, LineNumberTable and
		// SourceDebugExtension; the visitor below additionally drops SourceFile and MethodParameters.
		reader.accept(new DebugStrippingVisitor(writer), ClassReader.SKIP_DEBUG);
		return writer.toByteArray();
	}

	private static byte[] readAll(ZipFile zip, ZipEntry entry) throws IOException {
		try (InputStream in = zip.getInputStream(entry)) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, (int) entry.getSize()));
			in.transferTo(buffer);
			return buffer.toByteArray();
		}
	}

	private static final class DebugStrippingVisitor extends ClassVisitor {
		DebugStrippingVisitor(ClassVisitor next) {
			super(Opcodes.ASM9, next);
		}

		@Override
		public void visitSource(String source, String debug) {
			// Drop SourceFile / SourceDebugExtension entirely (they carry the original .java name).
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

			if (delegate == null) {
				return null;
			}

			return new MethodVisitor(Opcodes.ASM9, delegate) {
				@Override
				public void visitParameter(String parameterName, int parameterAccess) {
					// Drop MethodParameters entries (original parameter names).
				}
			};
		}
	}
}
