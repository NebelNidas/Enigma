package cuchaz.enigma.source.jadx;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import jadx.api.ICodeInfo;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.api.metadata.annotations.VarNode;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.TypeDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

class JadxHelper {
	private Map<ClassNode, String> internalNames = new HashMap<>();
	private Map<ClassNode, ClassEntry> classMap = new HashMap<>();
	private Map<FieldNode, FieldEntry> fieldMap = new HashMap<>();
	private Map<MethodNode, MethodEntry> methodMap = new HashMap<>();
	private Map<VarNode, LocalVariableEntry> localMap = new HashMap<>();
	private Map<MethodNode, LinkedHashMap<VarNode, Integer>> jadxArgMap = new HashMap<>();

	private String internalNameOf(ClassNode cls) {
		return internalNames.computeIfAbsent(cls, (unused) -> cls.getClassInfo().makeRawFullName().replace('.', '/'));
	}

	ClassEntry classEntryOf(ClassNode cls) {
		if (cls == null) return null;
		return classMap.computeIfAbsent(cls, (unused) -> new ClassEntry(internalNameOf(cls)));
	}

	FieldEntry fieldEntryOf(FieldNode fld) {
		return fieldMap.computeIfAbsent(fld, (unused) ->
				new FieldEntry(classEntryOf(fld.getParentClass()), fld.getName(), new TypeDescriptor(TypeGen.signature(fld.getType()))));
	}

	MethodEntry methodEntryOf(MethodNode mth) {
		return methodMap.computeIfAbsent(mth, (unused) -> {
			MethodInfo mthInfo = mth.getMethodInfo();
			MethodDescriptor desc = new MethodDescriptor(mthInfo.getShortId().substring(mthInfo.getName().length()));
			return new MethodEntry(classEntryOf(mth.getParentClass()), mthInfo.getName(), desc);
		});
	}

	@Nullable
	LocalVariableEntry paramEntryOf(VarNode param, ICodeInfo codeInfo) {
		return localMap.computeIfAbsent(param, (unused) -> {
			MethodEntry owner = methodEntryOf(param.getMth());
			Integer index = getMethodArgs(param.getMth(), codeInfo).get(param);

			if (index == null) {
				System.err.println("Parameter node not found: " + param);
			}

			return index == null || index < 0 ? null : new LocalVariableEntry(owner, index, param.getName(), true, null);
		});
	}

	LinkedHashMap<VarNode, Integer> getMethodArgs(MethodNode mth, ICodeInfo codeInfo) {
		return jadxArgMap.computeIfAbsent(mth, (unused) -> {
			int mthDefPos = mth.getDefPosition();
			LinkedHashMap<VarNode, Integer> args = new LinkedHashMap<>();
			AtomicInteger doneCount = new AtomicInteger(0);

			codeInfo.getCodeMetadata().searchDown(mthDefPos, (pos, ann) -> {
				if (ann instanceof NodeDeclareRef ref && ref.getNode() instanceof VarNode varNode) {
					if (varNode.getName().equals("entrypoint")) {
						int i = 0;
					}

					if (!varNode.getMth().equals(mth)) {
						// Stop if we've gone too far and have entered a different method
						return Boolean.TRUE;
					}

					if (varNode.getReg() >= mth.getArgsStartReg()) {
						// Skip if we've not reached the arguments yet
						return Boolean.FALSE;
					}

					if (!varNode.getType().equals(mth.getArgTypes().get(doneCount.get()))) {
						// Stop if the type doesn't match
						System.err.println("Type mismatch: " + varNode.getType() + " != " + mth.getArgTypes().get(doneCount.get()));
						return Boolean.TRUE;
					}

					Integer lvIndex = varNode.getReg() - mth.getArgsStartReg();
					args.put(varNode, lvIndex);
					doneCount.incrementAndGet();
				}

				return Boolean.TRUE;
			});

			return args;
		});
	}

	boolean isRecord(ClassNode cls) {
		if (cls.getSuperClass() == null || !cls.getSuperClass().isObject()) {
			return false;
		}

		return Objects.equals(cls.getSuperClass().getObject(), "java/lang/Record");
	}
}
