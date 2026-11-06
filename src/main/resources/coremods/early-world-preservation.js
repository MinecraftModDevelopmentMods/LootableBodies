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
    var writeDesc = '(L' + save + ';Lnet/minecraft/nbt/CompoundTag;)V';
    var attachedName = 'lootablebodies$preservationAttached';
    var attachedFlags = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
    return {
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
