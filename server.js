const http = require("http");
const WebSocket = require("ws");

const port = Number(process.env.PORT || 8080);
const host = process.env.HOST || "0.0.0.0";

const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    const body = JSON.stringify({
      ok: true,
      service: "tv-phone-signaling-server",
      onlineClients: clientsById.size,
      activeCalls: Math.floor(activePeerByClient.size / 2),
      pendingInvites: pendingInviteByTarget.size,
    });

    res.writeHead(200, {
      "Content-Type": "application/json; charset=utf-8",
      "Content-Length": Buffer.byteLength(body),
      "Cache-Control": "no-store",
    });
    res.end(body);
    return;
  }

  const body = JSON.stringify({
    service: "tv-phone-signaling-server",
    websocket: true,
    health: "/health",
    onlineClients: clientsById.size,
  });

  res.writeHead(200, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
});

const wss = new WebSocket.Server({ noServer: true });

const clientsById = new Map();
const socketToId = new Map();
const pendingInviteByTarget = new Map();
const activePeerByClient = new Map();

function sendJson(socket, payload) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  socket.send(JSON.stringify(payload));
}

function peerSnapshotFor(id) {
  return Array.from(clientsById.values())
    .filter((client) => client.id !== id)
    .map((client) => ({
      id: client.id,
      name: client.name,
      code: client.code,
      platform: client.platform,
    }))
    .sort((a, b) => a.code.localeCompare(b.code) || a.name.localeCompare(b.name));
}

function broadcastPeerLists() {
  for (const client of clientsById.values()) {
    sendJson(client.socket, {
      type: "peer_list",
      peers: peerSnapshotFor(client.id),
    });
  }
}

function clearPendingInvitesFor(clientId) {
  for (const [targetId, callerId] of pendingInviteByTarget.entries()) {
    if (callerId === clientId || targetId === clientId) {
      pendingInviteByTarget.delete(targetId);
      const counterpartId = callerId === clientId ? targetId : callerId;
      const counterpart = clientsById.get(counterpartId);
      if (counterpart) {
        sendJson(counterpart.socket, {
          type: "ended",
          byId: clientId,
        });
      }
    }
  }
}

function clearActiveCallFor(clientId) {
  const peerId = activePeerByClient.get(clientId);
  if (!peerId) return;

  activePeerByClient.delete(clientId);
  activePeerByClient.delete(peerId);

  const peer = clientsById.get(peerId);
  if (peer) {
    sendJson(peer.socket, {
      type: "ended",
      byId: clientId,
    });
  }
}

function unregisterClient(socket) {
  const clientId = socketToId.get(socket);
  if (!clientId) return;

  clearPendingInvitesFor(clientId);
  clearActiveCallFor(clientId);

  socketToId.delete(socket);
  clientsById.delete(clientId);
  broadcastPeerLists();
}

function registerClient(socket, message) {
  const id = String(message.deviceId || "").trim();
  const name = String(message.deviceName || "").trim();
  const code = String(message.deviceCode || "").trim();
  const platform = String(message.platform || "phone").trim().toLowerCase();

  if (!id || !name || !code) {
    sendJson(socket, {
      type: "error",
      message: "Register payload tidak lengkap.",
    });
    return;
  }

  const existing = clientsById.get(id);
  if (existing && existing.socket !== socket) {
    try {
      existing.socket.close(4001, "Duplicate login");
    } catch (error) {
    }
  }

  const client = { id, name, code, platform, socket };
  clientsById.set(id, client);
  socketToId.set(socket, id);

  sendJson(socket, { type: "registered" });
  broadcastPeerLists();
}

function handleInvite(senderId, message) {
  const targetId = String(message.toId || "");
  const caller = clientsById.get(senderId);
  const target = clientsById.get(targetId);

  if (!caller || !target) {
    sendJson(caller?.socket, {
      type: "error",
      message: "Perangkat tujuan tidak online.",
    });
    return;
  }

  if (
    activePeerByClient.has(senderId) ||
    activePeerByClient.has(targetId) ||
    pendingInviteByTarget.has(targetId)
  ) {
    sendJson(caller.socket, {
      type: "busy",
      byId: targetId,
    });
    return;
  }

  pendingInviteByTarget.set(targetId, senderId);
  sendJson(target.socket, {
    type: "incoming",
    from: {
      id: caller.id,
      name: caller.name,
      code: caller.code,
      platform: caller.platform,
    },
  });
}

function handleAccept(receiverId, message) {
  const callerId = String(message.toId || "");
  const receiver = clientsById.get(receiverId);
  const caller = clientsById.get(callerId);

  if (!receiver || !caller) {
    sendJson(receiver?.socket, {
      type: "error",
      message: "Pemanggil sudah offline.",
    });
    return;
  }

  if (pendingInviteByTarget.get(receiverId) !== callerId) {
    sendJson(receiver.socket, {
      type: "error",
      message: "Invite sudah tidak valid.",
    });
    return;
  }

  pendingInviteByTarget.delete(receiverId);
  activePeerByClient.set(receiverId, callerId);
  activePeerByClient.set(callerId, receiverId);

  sendJson(caller.socket, {
    type: "accepted",
    peer: {
      id: receiver.id,
      name: receiver.name,
      code: receiver.code,
      platform: receiver.platform,
    },
  });
}

function handleReject(receiverId, message) {
  const callerId = String(message.toId || "");
  if (pendingInviteByTarget.get(receiverId) !== callerId) return;

  pendingInviteByTarget.delete(receiverId);
  const caller = clientsById.get(callerId);
  if (caller) {
    sendJson(caller.socket, {
      type: "rejected",
      byId: receiverId,
    });
  }
}

function handleBusy(receiverId, message) {
  const callerId = String(message.toId || "");
  if (pendingInviteByTarget.get(receiverId) !== callerId) return;

  pendingInviteByTarget.delete(receiverId);
  const caller = clientsById.get(callerId);
  if (caller) {
    sendJson(caller.socket, {
      type: "busy",
      byId: receiverId,
    });
  }
}

function handleEnd(senderId, message) {
  const targetId = String(message.toId || "");

  if (activePeerByClient.get(senderId) === targetId) {
    activePeerByClient.delete(senderId);
    activePeerByClient.delete(targetId);

    const target = clientsById.get(targetId);
    if (target) {
      sendJson(target.socket, {
        type: "ended",
        byId: senderId,
      });
    }
    return;
  }

  if (pendingInviteByTarget.get(targetId) === senderId) {
    pendingInviteByTarget.delete(targetId);
    const target = clientsById.get(targetId);
    if (target) {
      sendJson(target.socket, {
        type: "ended",
        byId: senderId,
      });
    }
  }
}

function forwardAudio(senderId, data, isBinary) {
  if (!isBinary) return;
  const peerId = activePeerByClient.get(senderId);
  if (!peerId) return;

  const peer = clientsById.get(peerId);
  if (!peer || peer.socket.readyState !== WebSocket.OPEN) return;
  peer.socket.send(data, { binary: true });
}

server.on("upgrade", (request, socket, head) => {
  wss.handleUpgrade(request, socket, head, (webSocket) => {
    wss.emit("connection", webSocket, request);
  });
});

wss.on("connection", (socket) => {
  socket.on("message", (data, isBinary) => {
    const senderId = socketToId.get(socket);

    if (isBinary) {
      if (senderId) {
        forwardAudio(senderId, data, true);
      }
      return;
    }

    let message;
    try {
      message = JSON.parse(data.toString());
    } catch (error) {
      sendJson(socket, {
        type: "error",
        message: "Payload bukan JSON yang valid.",
      });
      return;
    }

    switch (message.type) {
      case "register":
        registerClient(socket, message);
        break;
      case "invite":
        if (senderId) handleInvite(senderId, message);
        break;
      case "accept":
        if (senderId) handleAccept(senderId, message);
        break;
      case "reject":
        if (senderId) handleReject(senderId, message);
        break;
      case "busy":
        if (senderId) handleBusy(senderId, message);
        break;
      case "end":
        if (senderId) handleEnd(senderId, message);
        break;
      default:
        sendJson(socket, {
          type: "error",
          message: `Tipe pesan tidak dikenal: ${message.type}`,
        });
        break;
    }
  });

  socket.on("close", () => {
    unregisterClient(socket);
  });

  socket.on("error", () => {
    unregisterClient(socket);
  });
});

server.listen(port, host, () => {
  console.log(`TV Phone signaling server listening on http://${host}:${port}`);
  console.log(`Health endpoint ready at http://${host}:${port}/health`);
  console.log(`WebSocket endpoint ready at ws://${host}:${port}`);
});
