import { _patcherDelaySymbol, instead } from "@api/patcher";
import { logger } from "@lib/utils/logger";
import { createSimpleFilter } from "@metro/factories";
import { createLazyModule } from "@metro/lazy";

// celeste voice nodes run without DAVE; the client refuses protocol version 0 with close 4804 unless allowed here
const RTCConnection = createLazyModule(createSimpleFilter(
    m => typeof m?.prototype?._ensureDaveProtocolVersionAllowed === "function",
    "celeste.daveDowngrade"
));

export default function patchDaveDowngrade() {
    let unpatch: () => unknown = () => { };
    const apply = (mod: any) => {
        try {
            const cls = typeof mod?.prototype?._ensureDaveProtocolVersionAllowed === "function" ? mod : mod?.default;
            // signature (stage, protocolVersion, transitionId), see the web bundle's _ensureDaveProtocolVersionAllowed
            unpatch = instead("_ensureDaveProtocolVersionAllowed", cls.prototype, function (this: any, args: any[], orig: Function) {
                return args[1] === 0 ? true : orig.apply(this, args);
            });
            logger.log("Celeste: DAVE protocol version 0 allowed");
        } catch (e) {
            logger.error("Celeste: failed to allow DAVE protocol version 0", e);
        }
    };

    try {
        const delay = RTCConnection[_patcherDelaySymbol];
        if (typeof delay === "function") delay(apply);
        else apply(RTCConnection);
    } catch (e) {
        logger.error("Celeste: RTCConnection not found, DAVE downgrade stays refused", e);
    }

    return () => unpatch();
}
