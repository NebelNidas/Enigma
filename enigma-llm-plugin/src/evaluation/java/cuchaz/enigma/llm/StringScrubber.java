package cuchaz.enigma.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Produces the <em>structure-only</em> track jar: a copy of the obfuscated jar with every program
 * string value blanked to {@code ""}. It isolates what the model can recover from bytecode structure
 * alone, so the difference against the realistic track (strings intact — faithful to how ProGuard/R8
 * and Minecraft leave string literals untouched) measures the contribution of string context.
 *
 * <p>Two string sources reach the suggestion prompt and are both blanked: {@code ldc} string constants
 * (which also feed the static-initializer hints the index derives) and {@code String} field
 * {@code ConstantValue} attributes. The literal is emptied, not removed, so the prompt still sees "a
 * string was here" and its token count is comparable across tracks — the two jars then differ in string
 * <em>payload</em> only, never in structure.
 *
 * <p>The rewrite is done at ASM level rather than by patching constant-pool {@code CONSTANT_Utf8}
 * bytes: a UTF-8 entry can be shared between a {@code CONSTANT_String} and a name/descriptor/attribute,
 * so an in-place payload edit could corrupt identifiers. ASM rebuilds the pool cleanly, touching only
 * the constants routed through {@link MethodVisitor#visitLdcInsn} and {@link ClassVisitor#visitField}.
 * Annotation string values are intentionally not touched: the index and prompt never surface them (and
 * the leak audit does not read them), so they neither reach the model nor affect the comparison.
 */
final class StringScrubber {
	private StringScrubber() {
	}

	/** Writes {@code outJar} = {@code inJar} with all program string values blanked; classes only. */
	static void scrub(Path inJar, Path outJar) throws IOException {
		Files.deleteIfExists(outJar);

		if (outJar.getParent() != null) {
			Files.createDirectories(outJar.getParent());
		}

		try (ZipFile zip = new ZipFile(inJar.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outJar))) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				out.putNextEntry(new ZipEntry(entry.getName()));

				if (!entry.isDirectory()) {
					copyEntry(zip, entry, out);
				}

				out.closeEntry();
			}
		}
	}

	private static void copyEntry(ZipFile zip, ZipEntry entry, OutputStream out) throws IOException {
		byte[] bytes;

		try (InputStream in = zip.getInputStream(entry)) {
			bytes = in.readAllBytes();
		}

		if (entry.getName().endsWith(".class")) {
			bytes = scrubClass(bytes);
		}

		out.write(bytes);
	}

	private static byte[] scrubClass(byte[] bytes) {
		ClassReader reader = new ClassReader(bytes);
		// Blanking "x" -> "" leaves stack/frame shapes unchanged, so no recomputation is needed.
		ClassWriter writer = new ClassWriter(0);
		reader.accept(new ScrubbingVisitor(writer), 0);
		return writer.toByteArray();
	}

	private static final class ScrubbingVisitor extends ClassVisitor {
		ScrubbingVisitor(ClassVisitor delegate) {
			super(Opcodes.ASM9, delegate);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			return super.visitField(access, name, descriptor, signature, value instanceof String ? "" : value);
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
				public void visitLdcInsn(Object value) {
					super.visitLdcInsn(value instanceof String ? "" : value);
				}
			};
		}
	}
}
