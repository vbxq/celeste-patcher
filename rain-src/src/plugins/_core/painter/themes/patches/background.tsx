import { _patcherDelaySymbol, after } from "@api/patcher";
import { findInReactTree } from "@lib/utils";
import { logger } from "@lib/utils/logger";
import { findByFilePathLazy } from "@metro";
import { useColorsPref } from "@plugins/_core/painter/themes/preferences";
import { _colorRef } from "@plugins/_core/painter/themes/updater";
import chroma from "chroma-js";
import { ImageBackground, StyleSheet } from "react-native";

const MessagesModule = findByFilePathLazy("modules/messages/native/Messages.tsx");

function ThemeBackground({ children }: { children: React.ReactNode; }) {
    const customBackground = useColorsPref(state => state.customBackground);

    if (!_colorRef.current
        || customBackground === "hidden"
        || !_colorRef.current.background?.url
        || _colorRef.current.background?.blur && (typeof _colorRef.current.background?.blur !== "number")
    ) {
        return children;
    }

    return <ImageBackground
        style={{ flex: 1, height: "100%" }}
        source={{ uri: _colorRef.current.background?.url }}
        blurRadius={_colorRef.current.background?.blur}
    >
        {children}
    </ImageBackground>;
}

type RenderCallback = (args: any[], ret: any) => any;

// discord 354 turned Messages into a plain function component, so pick the render target once the module loads;
// a throw here would surface inside the module require of the chat screen and crash it
function patchMessagesRender(callback: RenderCallback) {
    let cancelled = false;
    let unpatch: () => unknown = () => { };

    const apply = (mod: any) => {
        if (cancelled) return;
        try {
            const comp = mod?.default;
            if (typeof comp?.render === "function") unpatch = after("render", comp, callback);
            else if (typeof comp?.type?.render === "function") unpatch = after("render", comp.type, callback);
            else if (typeof comp?.prototype?.render === "function") unpatch = after("render", comp.prototype, callback);
            else if (typeof comp?.type === "function") unpatch = after("type", comp, callback);
            else if (typeof comp === "function") unpatch = after("default", mod, callback);
            else logger.warn("Chat background: unknown Messages component shape, not patching");
        } catch (e) {
            logger.error("Failed to patch chat background.", e);
        }
    };

    const delay = MessagesModule?.[_patcherDelaySymbol];
    if (typeof delay === "function") delay(apply);
    else apply(MessagesModule);

    return () => {
        cancelled = true;
        unpatch();
    };
}

export default function patchChatBackground() {
    try {
        const patches = [
            patchMessagesRender((_, ret) => {
                if (!_colorRef.current || !_colorRef.current.background?.url) return;
                const messagesComponent = findInReactTree(
                    ret,
                    x => x && "HACK_fixModalInteraction" in x.props && x?.props?.style
                );
                if (messagesComponent) {
                    const flattened = StyleSheet.flatten(messagesComponent.props.style);
                    const backgroundColor = chroma(
                        flattened.backgroundColor || "black"
                    ).alpha(
                        1 - (_colorRef.current.background?.opacity ?? 1)
                    ).hex();
                    messagesComponent.props.style = StyleSheet.flatten([
                        messagesComponent.props.style,
                        { backgroundColor }
                    ]);
                }
                return <ThemeBackground>{ret}</ThemeBackground>;
            })
        ];
        return () => patches.forEach(x => x());
    } catch (e) {
        logger.error("Failed to patch chat background.", e);
        return () => { };
    }
}
