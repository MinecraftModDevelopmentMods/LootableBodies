// SPDX-License-Identifier: LGPL-2.1-only
// Preserve originals before the shared native level-data reader and
// retain our own field before Forge writes the world header.
function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var MethodNode = Java.type('org.objectweb.asm.tree.MethodNode');
    var save = 'net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess';
    var hook = 'cyano/lootable/upgrade/EarlyWorldPreservation';
    var readDesc = '(L' + save + ';Z)V';
    var nativeReadDesc = '()Lcom/mojang/serialization/Dynamic;';
    var dedicatedReadDesc = '(L' + save + ';Z)Lcom/mojang/serialization/Dynamic;';
    var writeDesc = '(L' + save + ';Lnet/minecraft/nbt/CompoundTag;)V';
    var attachedName = 'lootablebodies$preservationAttached';
    var attachedFlags = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
    var fixer = 'net/minecraft/util/datafix/DataFixers';
    var fixerType = 'Lcom/mojang/datafixers/DataFixer;';
    var wrapper = 'cyano/lootable/upgrade/UpgradePreservation';
    var wrapperDesc = '(' + fixerType + ')' + fixerType;
    return {
        lootablebodies_dedicated_read: {
            target: {type: 'CLASS', name: 'net.minecraft.server.Main'},
            transformer: function(node) {
                if (String(node.name) !== 'net/minecraft/server/Main') throw new Error('Unexpected dedicated server class');
                var main = null;
                for (var m = 0; m < node.methods.size(); m++) {
                    var method = node.methods.get(m);
                    if (String(method.name) !== 'main' || String(method.desc) !== '([Ljava/lang/String;)V' ||
                            (method.access & Opcodes.ACC_STATIC) === 0) continue;
                    if (main !== null) throw new Error('Ambiguous dedicated server entry point');
                    main = method;
                }
                if (main === null) throw new Error('Missing dedicated server entry point');
                var first = null, fallback = null, guarded = 0;
                for (var insn = main.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode)) continue;
                    if (String(insn.owner) === hook && String(insn.name) === 'readDedicatedWorld' &&
                            String(insn.desc) === dedicatedReadDesc && insn.getOpcode() === Opcodes.INVOKESTATIC) guarded++;
                    if (String(insn.owner) !== save || String(insn.desc) !== nativeReadDesc ||
                            insn.getOpcode() !== Opcodes.INVOKEVIRTUAL) continue;
                    if (String(insn.name) === 'getDataTag' && first === null) first = insn;
                    else if (String(insn.name) === 'getDataTagFallback' && fallback === null) fallback = insn;
                    else throw new Error('Unexpected or duplicate dedicated world reader');
                }
                if (guarded === 2 && first === null && fallback === null) return node;
                if (guarded !== 0 || first === null || fallback === null) throw new Error('Dedicated world readers are missing or ambiguous');
                [first, fallback].forEach(function(reader, index) {
                    main.instructions.insertBefore(reader, new InsnNode(index === 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
                    main.instructions.set(reader, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, hook, 'readDedicatedWorld', dedicatedReadDesc, false));
                });
                main.maxStack += 1;
                return node;
            }
        },
        lootablebodies_fixer: {
            target: {type: 'CLASS', name: fixer.replace(/\//g, '.')},
            transformer: function(node) {
                if (String(node.name) !== fixer) throw new Error('Unexpected native data fixer class');
                var accessor = null;
                for (var m = 0; m < node.methods.size(); m++) {
                    var method = node.methods.get(m);
                    if (String(method.name) !== 'getDataFixer' || String(method.desc) !== '()' + fixerType ||
                            (method.access & Opcodes.ACC_STATIC) === 0) continue;
                    if (accessor !== null) throw new Error('Ambiguous native data fixer accessor');
                    accessor = method;
                }
                if (accessor === null) throw new Error('Missing native data fixer accessor');
                var returns = [], wraps = 0;
                for (var insn = accessor.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                    if (insn.getOpcode() === Opcodes.ARETURN) returns.push(insn);
                    if (insn instanceof MethodInsnNode && String(insn.owner) === wrapper) {
                        if (String(insn.name) !== 'wrapper' || String(insn.desc) !== wrapperDesc)
                            throw new Error('Unexpected data fixer wrapper signature');
                        wraps++;
                    }
                }
                if (returns.length !== 1 || wraps > 1) throw new Error('Ambiguous native data fixer return');
                if (wraps === 0)
                    accessor.instructions.insertBefore(returns[0], new MethodInsnNode(
                        Opcodes.INVOKESTATIC, wrapper, 'wrapper', wrapperDesc, false));
                return node;
            }
        },
        lootablebodies_preservation: {
            target: {type: 'CLASS', name: save.replace(/\//g, '.')},
            transformer: function(node) {
                if (String(node.name) !== save) throw new Error('Unexpected native save class');
                var read = null, readCount = 0, writes = [], oldRead = 0, oldWrite = 0, attached = 0;
                for (var m = 0; m < node.methods.size(); m++) {
                    var method = node.methods.get(m);
                    if (String(method.name) === attachedName && String(method.desc) === '()Z' && (method.access & attachedFlags) === attachedFlags) attached++;
                    if ((String(method.name) === 'getDataTag' || String(method.name) === 'm_246049_') &&
                            String(method.desc) === '(Z)Lcom/mojang/serialization/Dynamic;' &&
                            (method.access & Opcodes.ACC_STATIC) === 0) {
                        read = method; readCount++;
                    }
                    for (var instruction = method.instructions.getFirst(); instruction !== null; instruction = instruction.getNext()) {
                        if (!(instruction instanceof MethodInsnNode)) continue;
                        if (String(instruction.owner) === hook) {
                            if (String(instruction.name) === 'beforeRead' && String(instruction.desc) === readDesc) oldRead++;
                            else if (String(instruction.name) === 'beforeWrite' && String(instruction.desc) === writeDesc) oldWrite++;
                            else throw new Error('Unexpected preservation hook signature');
                        }
                        if (String(instruction.owner) === 'net/minecraftforge/common/ForgeHooks' &&
                                String(instruction.name) === 'writeAdditionalLevelSaveData' &&
                                String(instruction.desc) === '(Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V') {
                            if ((method.access & Opcodes.ACC_STATIC) !== 0) throw new Error('Native save method became static');
                            writes.push({method: method, instruction: instruction});
                        }
                    }
                }
                if (readCount !== 1 || writes.length !== 1) throw new Error('Native early read/write boundary is missing or ambiguous');
                if (oldRead === 1 && oldWrite === 1 && attached === 1) return node;
                if (oldRead !== 0 || oldWrite !== 0 || attached !== 0) throw new Error('Partial or duplicate preservation hooks');
                var beforeRead = new InsnList();
                beforeRead.add(new VarInsnNode(Opcodes.ALOAD, 0));
                beforeRead.add(new VarInsnNode(Opcodes.ILOAD, 1));
                beforeRead.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook, 'beforeRead', readDesc, false));
                read.instructions.insert(beforeRead);
                read.maxStack = Math.max(read.maxStack, 2);
                // Existing stack: WorldData, CompoundTag. Keep those operands
                // for Forge, while passing this save access and a duplicate tag.
                var beforeWrite = new InsnList();
                beforeWrite.add(new InsnNode(Opcodes.DUP));
                beforeWrite.add(new VarInsnNode(Opcodes.ALOAD, 0));
                beforeWrite.add(new InsnNode(Opcodes.SWAP));
                beforeWrite.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook, 'beforeWrite', writeDesc, false));
                writes[0].method.instructions.insertBefore(writes[0].instruction, beforeWrite);
                writes[0].method.maxStack = Math.max(writes[0].method.maxStack, 4);
                // The native engine catches ordinary script exceptions. Serial
                // setup must require this method before a world can be opened.
                var marker = new MethodNode(attachedFlags, attachedName, '()Z', null, null);
                marker.instructions.add(new InsnNode(Opcodes.ICONST_1));
                marker.instructions.add(new InsnNode(Opcodes.IRETURN));
                marker.maxStack = 1; marker.maxLocals = 0;
                node.methods.add(marker);
                return node;
            }
        }
    };
}
