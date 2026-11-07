// SPDX-License-Identifier: LGPL-2.1-only
function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var preservation = 'cyano/lootable/upgrade/UpgradePreservation';
    var observer = 'cyano/lootable/buildtest/NativePreflightChecks';
    var fixer = 'Lcom/mojang/datafixers/DataFixer;';
    return {
        lootablebodies_observe_preflight: {
            target: {type: 'CLASS', name: preservation.replace(/\//g, '.')},
            transformer: function(node) {
                if (String(node.name) !== preservation) throw new Error('Unexpected preflight class');
                var accessor = null, target = null;
                for (var m = 0; m < node.methods.size(); m++) {
                    var method = node.methods.get(m);
                    if (String(method.name) !== 'prepareEarly' || String(method.desc) !==
                            '(Ljava/nio/file/Path;Ljava/nio/file/Path;)Lcyano/lootable/upgrade/UpgradeJournal;') continue;
                    for (var insn = method.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                        if (!(insn instanceof MethodInsnNode) || insn.getOpcode() !== Opcodes.INVOKESTATIC ||
                                String(insn.owner) !== 'net/minecraft/util/datafix/DataFixers' ||
                                String(insn.name) !== 'getDataFixer' || String(insn.desc) !== '()' + fixer) continue;
                        if (accessor !== null) throw new Error('Ambiguous preflight data fixer lookup');
                        accessor = insn;
                        target = method;
                    }
                }
                if (accessor === null) throw new Error('Missing preflight data fixer lookup');
                target.instructions.insert(accessor, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, observer, 'observe', '(' + fixer + ')' + fixer, false));
                return node;
            }
        }
    };
}
