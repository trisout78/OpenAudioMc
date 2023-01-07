/* global io */

import {getGlobalState, setGlobalState} from "../../../state/store";
import {TimeService} from "../time/TimeService";
import {MediaManager} from "../media/MediaManager";
import {toast} from "react-toastify";
import {HandlerRegistry} from "./HandlerRegistry";

export const SocketManager = new class ISocketManager {

    constructor() {
        this.handlers = {};
        this.callbacksEnabled = false;
        this.supportsYoutube = false;
        this.hasConnected = false;
        this.outgoingQueue = [];
        this.inCount = 0;
        this.outCount = 0;

        this.connectToServer = this.connectToServer.bind(this);
        this.registerHandler = this.registerHandler.bind(this);
        new HandlerRegistry(this);
    }

    connectToServer(endpoint) {
        setGlobalState({relay: {connecting: true}});

        let currentUser = getGlobalState().currentUser;
        const authHeader = "" +
            "type=client&" +
            "n=" + currentUser.userName + "&" +
            "player=" + currentUser.uuid + "&" +
            "s=" + currentUser.publicServerKey + "&" +
            "p=" + currentUser.token;

        this.socket = io(endpoint, {query: authHeader, autoConnect: false, withCredentials: false});

        this.socket.on("connect", () => {
            toast("Connected to the server!", {icon: "🎉"});
            setGlobalState({relay: {connected: true, connecting: false}});
            this.outgoingQueue.forEach((waiting) => {
                this.send(waiting.key, waiting.value);
            });
        });

        this.socket.on("time-update", (time) => {
            let data = time.split(":");
            let hoursOffset = parseInt(data[1]);
            let timeStamp = parseInt(data[0]);
            TimeService.sync(timeStamp, hoursOffset);
        });

        this.socket.on("disconnect", () => {
            MediaManager.destroySounds(null, true);

            toast.error('👋 Disconnected from the server, goodbye!', {
                position: "top-center",
                autoClose: 5000,
                hideProgressBar: false,
                closeOnClick: true,
                pauseOnHover: true,
                draggable: true,
                progress: undefined,
                theme: "dark",
            });

            setGlobalState({relay: {connected: false, connecting: false}, currentUser: null, isLoading: false});

            // main.voiceModule.shutDown();
            // TODO: shutdown voicechat
        });

        this.socket.on("data", data => {
            let packages = data.type.split(".");
            let payloadType = packages[packages.length - 1];
            if (this.handlers[payloadType] != null) this.handlers[payloadType](data.payload);
            this.inCount++;
        });

        this.socket.connect();
    }

    send(event, data) {
        this.outCount++;
        if (this.hasConnected) {
            if (this.callbacksEnabled) {
                console.error("Submitting value for " + event);
                this.socket.emit(event, data);
            } else {
                console.error("could not satisfy callback " + event + " because the protocol is outdated");
                return
            }
        } else {
            this.outgoingQueue.push({
                key: event,
                value: data
            });
        }
    }

    registerHandler(channel, f) {
        this.handlers[channel] = f;
    }
}()