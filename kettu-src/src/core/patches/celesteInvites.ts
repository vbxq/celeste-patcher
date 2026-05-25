export default function patchCelesteInvites() {
    try {
        const modules = window.modules;
        if (!modules) { console.error("CELESTE_SCAN: no modules"); return; }

        let found = 0;
        for (const id in modules) {
            try {
                const mod = modules[id];
                if (!mod?.publicModule?.exports) continue;
                const exp = mod.publicModule.exports;

                const scan = (obj: any, prefix: string) => {
                    if (!obj || typeof obj !== "object") return;
                    for (const key of Object.keys(obj)) {
                        try {
                            const val = obj[key];
                            if (typeof val === "string" && val.includes("discord.gg")) {
                                console.error("CELESTE_SCAN module=" + id + " " + prefix + key + " = " + val);
                                found++;
                            }
                        } catch {}
                    }
                };

                scan(exp, "");
                if (exp.default && typeof exp.default === "object") scan(exp.default, "default.");
            } catch {}
        }

        console.error("CELESTE_SCAN: done, found " + found + " discord.gg refs");
    } catch (e) {
        console.error("CELESTE_SCAN: error " + e);
    }
}
