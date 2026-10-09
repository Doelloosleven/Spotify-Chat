package dev.spotifychat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Services;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Small bridge for things Minecraft moved between versions, so one source works on 26.1.2, 26.2 and 26.3:
 * 26.2+ has the current screen on {@code gui} and chat / action bar on {@code gui.hud};
 * 26.1.2 has the screen on {@code Minecraft} and chat / action bar on {@code Gui}.
 * The right one is looked up once (Minecraft 26.x isn't obfuscated, so the names are stable).
 */
public final class Mc {
    private Mc() {}

    private static final Field HUD = field(Gui.class, "hud");                         // 26.2+
    private static final Method GUI_SCREEN = method(Gui.class, "screen");             // 26.2+
    private static final Method GUI_SET_SCREEN = method(Gui.class, "setScreen", Screen.class);
    private static final Method GUI_OPEN_CHAT = method(Gui.class, "openChatScreen", ChatComponent.ChatMethod.class);
    private static final Field MC_SCREEN = field(Minecraft.class, "screen");          // 26.1.x
    private static final Method MC_SET_SCREEN = method(Minecraft.class, "setScreen", Screen.class);
    private static final Method MC_OPEN_CHAT =
            method(Minecraft.class, "openChatScreen", ChatComponent.ChatMethod.class);
    private static final Method SESSION_SERVICE = method(Services.class, "sessionService");

    /** Gui (26.1.x) or Hud (26.2+): the object with getChat() and setOverlayMessage() */
    private static Object chatOwner() {
        Gui gui = Minecraft.getInstance().gui;
        if (HUD == null) return gui;
        try {
            return HUD.get(gui);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Screen screen() {
        Minecraft mc = Minecraft.getInstance();
        return (Screen) (GUI_SCREEN != null ? call(GUI_SCREEN, mc.gui) : get(MC_SCREEN, mc));
    }

    public static void setScreen(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (GUI_SET_SCREEN != null) call(GUI_SET_SCREEN, mc.gui, screen);
        else call(MC_SET_SCREEN, mc, screen);
    }

    public static void openChat() {
        Minecraft mc = Minecraft.getInstance();
        if (GUI_OPEN_CHAT != null) call(GUI_OPEN_CHAT, mc.gui, ChatComponent.ChatMethod.MESSAGE);
        else call(MC_OPEN_CHAT, mc, ChatComponent.ChatMethod.MESSAGE);
    }

    public static ChatComponent chat() {
        Object owner = chatOwner();
        return (ChatComponent) call(method(owner.getClass(), "getChat"), owner);
    }

    /** The short message above the hotbar */
    public static void actionBar(Component text) {
        Object owner = chatOwner();
        call(method(owner.getClass(), "setOverlayMessage", Component.class, boolean.class), owner, text, false);
    }

    /**
     * Tells Mojang this account is joining serverId, the way the game does when joining a server, so
     * someone else can check with Mojang that you own your Minecraft name. The login itself only goes to
     * Mojang. sessionService() returns a renamed authlib type in 26.3, hence the reflection.
     */
    public static void joinServer(String serverId) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        Object sessionService = call(SESSION_SERVICE, mc.services());
        Method join = null;
        for (Class<?> c = sessionService.getClass(); c != null && join == null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                join = method(i, "joinServer", UUID.class, String.class, String.class);
                if (join != null) break;
            }
        }
        if (join == null) throw new IllegalStateException("Not available in this Minecraft version");
        try {
            join.invoke(sessionService, mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    // ------------------------------------------------------------ reflection

    private static Field field(Class<?> c, String name) {
        try {
            return c.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Method method(Class<?> c, String name, Class<?>... params) {
        try {
            return c.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object call(Method m, Object target, Object... args) {
        if (m == null) throw new IllegalStateException("Not available in this Minecraft version");
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(Field f, Object target) {
        if (f == null) throw new IllegalStateException("Not available in this Minecraft version");
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
