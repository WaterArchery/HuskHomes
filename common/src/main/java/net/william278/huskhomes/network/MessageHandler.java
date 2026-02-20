/*
 * This file is part of HuskHomes, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.huskhomes.network;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.api.BaseHuskHomesAPI;
import net.william278.huskhomes.position.*;
import net.william278.huskhomes.teleport.Teleport;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.user.User;
import net.william278.huskhomes.util.TransactionResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public interface MessageHandler {

    // Handle inbound user list requests
    default void handleRequestUserList(@NotNull Message message, @Nullable OnlineUser receiver) {
        if (receiver == null) {
            return;
        }

        Message.builder()
                .type(Message.MessageType.UPDATE_USER_LIST)
                .payload(Payload.userList(getPlugin().getOnlineUsers().stream().map(online -> (User) online).toList()))
                .target(message.getSourceServer(), Message.TargetType.SERVER).build()
                .send(getBroker(), receiver);
    }

    // Handle inbound user list updates (returned from requests)
    default void handleUpdateUserList(@NotNull Message message) {
        message.getPayload().getUserList().ifPresent(
                (players) -> getPlugin().setUserList(message.getSourceServer(), players)
        );
    }

    default void handleTeleportToPosition(@NotNull Message message, @NotNull OnlineUser receiver) {
        message.getPayload().getPosition().ifPresent(
                (position) -> Teleport.builder(getPlugin())
                        .teleporter(receiver)
                        .target(position)
                        .toTeleport()
                        .complete()
        );
    }

    default void handleTeleportToNetworkedPosition(@NotNull Message message, @NotNull OnlineUser receiver) {
        Message.builder()
                .type(Message.MessageType.TELEPORT_TO_POSITION)
                .target(message.getSender(), Message.TargetType.PLAYER)
                .payload(Payload.position(receiver.getPosition()))
                .build().send(getBroker(), receiver);
    }

    default void handleTeleportToNetworkedUser(@NotNull Message message, @NotNull OnlineUser receiver) {
        message.getPayload().getString().ifPresent(
                (target) -> Message.builder()
                        .type(Message.MessageType.TELEPORT_TO_NETWORKED_POSITION)
                        .target(target, Message.TargetType.PLAYER)
                        .build().send(getBroker(), receiver)
        );
    }

    default void handleTeleportRequest(@NotNull Message message, @NotNull OnlineUser receiver) {
        message.getPayload().getTeleportRequest().ifPresent(
                (request) -> getPlugin().getManager().requests()
                        .sendLocalTeleportRequest(request, receiver)
        );
    }

    default void handleTeleportRequestResponse(@NotNull Message message, @NotNull OnlineUser receiver) {
        message.getPayload().getTeleportRequest().ifPresent(
                (request) -> getPlugin().getManager().requests().handleLocalRequestResponse(receiver, request)
        );
    }

    default void handleUpdateHome(@NotNull Message message, @NotNull OnlineUser receiver) {
        message.getPayload().getString()
                .map(UUID::fromString)
                .ifPresent(id -> {
                    final Optional<Home> optionalHome = getPlugin().getDatabase().getHome(id);
                    if (optionalHome.isPresent()) {
                        getPlugin().getManager().homes().cacheHome(optionalHome.get(), false);
                    } else {
                        getPlugin().getPlugin().getManager().homes().unCacheHome(id, false);
                    }
                });
    }

    default void handleUpdateWarp(@NotNull Message message, @NotNull OnlineUser receiver) {
        message.getPayload().getString()
                .map(UUID::fromString)
                .ifPresent(warpId -> {
                    final Optional<Warp> optionalWarp = getPlugin().getDatabase().getWarp(warpId);
                    if (optionalWarp.isPresent()) {
                        getPlugin().getManager().warps().cacheWarp(optionalWarp.get(), false);
                    } else {
                        getPlugin().getManager().warps().unCacheWarp(warpId, false);
                    }
                });
    }

    default void handleRtpRequestLocation(@NotNull Message message) {
        final Optional<World> requested = message.getPayload().getString().flatMap(
                name -> getPlugin().getWorlds().stream().filter(w -> w.getName().equalsIgnoreCase(name)).findFirst());
        requested.map(world -> getPlugin().getRandomTeleportEngine().getRandomPosition(world, new String[0]))
                .orElse(CompletableFuture.completedFuture(Optional.empty()))
                .thenAccept(
                        (teleport) -> Message.builder()
                                .type(Message.MessageType.RTP_LOCATION)
                                .target(message.getSender(), Message.TargetType.PLAYER)
                                .payload(Payload.position(teleport.orElse(null)))
                                .build().send(getBroker(), null)
                );

    }

    default void handleRtpLocation(@NotNull Message message, @NotNull OnlineUser receiver) {
        final Optional<Position> position = message.getPayload().getPosition();
        if (position.isEmpty()) {
            getPlugin().getLocales().getLocale("error_rtp_randomization_timeout")
                    .ifPresent(receiver::sendMessage);
            return;
        }

        Teleport.builder(getPlugin())
                .teleporter(receiver)
                .actions(TransactionResolver.Action.RANDOM_TELEPORT)
                .target(position.get())
                .buildAndComplete(true);
    }

    default void handleApiRtpRequestLocation(@NotNull Message message) {
        HuskHomes plugin = getPlugin();
        plugin.log(Level.INFO, "Received API RTP location request: " + message.getPayload().getString().orElse("<invalid>"));
        String raw = message.getPayload().getString().orElse("");
        String[] parts = raw.split("\0");
        if (parts.length != 3) {
            getPlugin().log(Level.WARNING, "Invalid API RTP location request payload: " + raw);
            return;
        }

        String requestId = parts[0];
        int count = Integer.parseInt(parts[1]);
        String worldName = parts[2];

        World world = plugin.getWorlds().stream().filter(w -> w.getName().equalsIgnoreCase(worldName)).findFirst().orElse(null);
        plugin.getRandomTeleportEngine().getRandomPosition(world, new String[0]).thenAccept(position -> {
            if (position.isPresent()) {
                List<CompletableFuture<Optional<Location>>> futurePositions = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    Position randomized = randomizeAroundPosition(position.get());
                    futurePositions.add(plugin.findSafeGroundLocation(randomized));
                }

                CompletableFuture.allOf(futurePositions.toArray(new CompletableFuture[0])).thenAccept(v -> {
                    List<Location> safeLocations = new ArrayList<>();
                    for (CompletableFuture<Optional<Location>> future : futurePositions)
                        future.join().ifPresent(safeLocations::add);

                    List<Position> safePositions = new ArrayList<>();
                    for (Location safeLocation : safeLocations)
                        safePositions.add(Position.at(safeLocation.getX(), safeLocation.getY(),
                                safeLocation.getZ(), safeLocation.getWorld(), plugin.getServerName()));

                    plugin.log(Level.INFO, "Found " + safeLocations.size() + " safe locations for API RTP request: " + requestId);
                    Message.builder().type(Message.MessageType.API_RTP_LOCATION)
                            .target(message.getSourceServer(), Message.TargetType.SERVER)
                            .payload(Payload.rtpLocation(requestId, safePositions))
                            .build().send(getBroker(), null);
                });
            } else {
                plugin.log(Level.WARNING, "Failed to get RTP location for API request: " + requestId);
                Message.builder().type(Message.MessageType.API_RTP_LOCATION)
                        .target(message.getSourceServer(), Message.TargetType.SERVER)
                        .payload(Payload.rtpLocation(requestId, new ArrayList<>()))
                        .build().send(getBroker(), null);
            }
        });
    }

    private Position randomizeAroundPosition(Position position) {
        double radius = 15;
        double x = position.getX() + (Math.random() * 2 - 1) * radius;
        double y = position.getY();
        double z = position.getZ() + (Math.random() * 2 - 1) * radius;
        return Position.at(x, y, z, position.getWorld(), position.getServer());
    }

    default void handleApiRtpLocation(@NotNull Message message) {
        message.getPayload().getString().ifPresent(requestId -> BaseHuskHomesAPI.getInstance()
                .completeRtpLocationRequest(requestId, message.getPayload().getPositionList().orElse(new ArrayList<>())));
    }

    default void handleUpdateCaches() {
        getPlugin().getManager().homes().updatePublicHomeCache();
        getPlugin().getManager().warps().updateWarpCache();
    }

    @NotNull
    Broker getBroker();

    @NotNull
    HuskHomes getPlugin();

}
