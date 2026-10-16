package com.kevin.tiertagger.compat;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;

/** Click and hover actions of chat text. 1.21.5 turned them into one record per action. */
public final class ChatEvents {
    private ChatEvents() {}

    public static ClickEvent openUrl(URI uri) {
        //? if >=1.21.5 {
        return new ClickEvent.OpenUrl(uri);
        //?} else {
        /*return new ClickEvent(ClickEvent.Action.OPEN_URL, uri.toString());
        *///?}
    }

    public static ClickEvent runCommand(String command) {
        //? if >=1.21.5 {
        return new ClickEvent.RunCommand(command);
        //?} else {
        /*return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        *///?}
    }

    public static HoverEvent showText(Component text) {
        //? if >=1.21.5 {
        return new HoverEvent.ShowText(text);
        //?} else {
        /*return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
        *///?}
    }
}
