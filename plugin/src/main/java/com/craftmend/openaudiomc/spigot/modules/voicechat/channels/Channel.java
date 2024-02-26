package com.craftmend.openaudiomc.spigot.modules.voicechat.channels;

import com.craftmend.openaudiomc.api.VoiceApi;
import com.craftmend.openaudiomc.generic.client.objects.ClientConnection;
import com.craftmend.openaudiomc.generic.platform.Platform;
import com.craftmend.openaudiomc.generic.storage.enums.StorageKey;
import com.craftmend.openaudiomc.generic.user.User;
import com.craftmend.openaudiomc.spigot.modules.voicechat.VoiceChannelService;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Channel {

    @Nullable
    @Getter private User creator;
    @Getter private String name;
    private final Map<UUID, ClientConnection> members = new HashMap<>();
    private final VoiceChannelService voiceChannelService;
    private final String requiredPermission;
    @Getter private final ChannelType type;

    public Channel(@Nullable User creator, String name, VoiceChannelService voiceChannelService) {
        this.creator = creator;
        this.name = name;
        this.voiceChannelService = voiceChannelService;
        this.requiredPermission = null;
        this.type = ChannelType.USER_CHANNEL;
    }

    public Channel(String name, String permission, VoiceChannelService voiceChannelService) {
        this.name = name;
        this.voiceChannelService = voiceChannelService;
        this.requiredPermission = permission;
        type = permission != null ? ChannelType.STATIC_CHANNEL_LOCKED : ChannelType.STATIC_CHANNEL;
    }

    public ChannelEnterResponse attemptEnter(User member) {
        // admin check
        if (member.hasPermission("openaudiomc.channel.force-join") || member.isAdministrator()) {
            return ChannelEnterResponse.OK;
        }

        switch (type) {
            case USER_CHANNEL:
                return ChannelEnterResponse.INVITE_ONLY;
            case STATIC_CHANNEL:
            case STATIC_CHANNEL_LOCKED:
                if (requiredPermission == null || member.hasPermission(requiredPermission)) {
                    return ChannelEnterResponse.OK;
                } else {
                    return ChannelEnterResponse.NO_PERMISSION;
                }
        }

        // should never happen
        return ChannelEnterResponse.NO_PERMISSION;
    }

    public String getReadableType() {
        switch (type) {
            case USER_CHANNEL:
                return StorageKey.MESSAGE_VOICE_CHANNEL_TYPE_USER.getString();
            case STATIC_CHANNEL:
                return StorageKey.MESSAGE_VOICE_CHANNEL_TYPE_STATIC.getString();
            case STATIC_CHANNEL_LOCKED:
                return StorageKey.MESSAGE_VOICE_CHANNEL_TYPE_STATIC_LOCKED.getString();
        }
        return "Unknown";
    }

    public void addMember(User user) {
        // is this user already in a channel?
        if (members.containsKey(user.getUniqueId())) {
            // ignore
            return;
        }

        ClientConnection client = (ClientConnection) user.findClient().get();
        members.put(user.getUniqueId(), client);
        client.getRtcSessionManager().setCurrentChannel(this);

        // link everyone whoowh
        for (ClientConnection member : members.values()) {
            // add the players as mutual peers
            if (member.getUser().getUniqueId().equals(user.getUniqueId())) continue;
            VoiceApi.getInstance().addStaticPeer(member, client, true, true);
        }
    }

    public void removeMember(User user) {
        ClientConnection client = (ClientConnection) user.findClient().get();
        boolean wasPresent = members.remove(user.getUniqueId()) != null;
        if (!wasPresent) return;
        client.getRtcSessionManager().setCurrentChannel(null);

        // unlink everyone whoowh
        for (ClientConnection member : members.values()) {
            // remove the players as mutual peers
            if (member.getUser().getUniqueId().equals(user.getUniqueId())) continue;
            VoiceApi.getInstance().removeStaticPeer(member, client, true);
        }

        checkAbandonment();
    }

    private void checkAbandonment() {
        if (type != ChannelType.USER_CHANNEL) return;
        if (members.size() < 2) {
            members.forEach((uuid, clientConnection) -> clientConnection.getUser().sendMessage(Platform.translateColors(StorageKey.MESSAGE_VOICE_CHANNEL_ABANDONED.getString())));
            voiceChannelService.deleteChannel(name);
        }
    }

    public void drainMembers() {
        for (ClientConnection member : members.values()) {
            member.getRtcSessionManager().setCurrentChannel(null);
            for (ClientConnection other : members.values()) {
                if (other.getUser().getUniqueId().equals(member.getActor().getUniqueId())) continue;
                VoiceApi.getInstance().removeStaticPeer(member, other, true);
            }
            member.getUser().sendMessage(Platform.translateColors(StorageKey.MESSAGE_VOICE_CHANNEL_KICKED.getString()));
        }
        members.clear();
    }

    public Collection<ClientConnection> getMembers() {
        return members.values();
    }
}
