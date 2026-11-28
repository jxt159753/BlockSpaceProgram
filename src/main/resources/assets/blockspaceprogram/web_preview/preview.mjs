import { apoapsis, periapsis, period, pointOnOrbit, positionAt } from "./kepler.mjs";

const browserLanguage = navigator.language.toLowerCase();
const traditional = /^zh-(hant|tw|hk|mo)(-|$)/.test(browserLanguage);
const locale = traditional ? "zh-TW" : browserLanguage.startsWith("zh") ? "zh-CN" : "en-US";
const languageFile = locale.toLowerCase().replace("-", "_");
const language = await fetch("lang/" + languageFile + ".json").then(response => response.json());
const text = key => language["web_preview.blockspaceprogram.page." + key];
const $ = id => document.getElementById(id);
const map = $("map");
const focus = $("focus");
const timeline = $("timeline");
const bodies = new Map();
const ships = new Map();
const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 2 });
const origin = [0, 0, 0];
let selected = null;
let zoom = 1;
let frame;
let clock;
let previewAnchor = null;
let connected = false;

document.documentElement.lang = locale;
document.title = text("title") + " · BlockSpaceProgram";
for (const element of document.querySelectorAll("[data-text]")) {
    element.textContent = text(element.dataset.text);
}
for (const element of document.querySelectorAll("[data-label]")) {
    element.setAttribute("aria-label", text(element.dataset.label));
}

function bodyName(id) {
    return text("body." + id) ?? id;
}

function componentText(component) {
    if (typeof component === "string") return component;
    if (Array.isArray(component)) return component.map(componentText).join("");
    if (!component || typeof component !== "object") return "";
    let label = component.text ?? "";
    if (component.translate) {
        let argument = 0;
        const template = language[component.translate] ?? component.fallback ?? component.translate;
        label = template.replace(/%%|%(?:(\d+)\$)?s/g, (match, index) =>
            match === "%%" ? "%" : componentText(component.with?.[index ? Number(index) - 1 : argument++]));
    }
    return label + componentText(component.extra);
}

function shipName(id, serialized) {
    if (serialized) {
        try {
            const name = componentText(JSON.parse(serialized));
            if (name) return name;
        } catch {
            // Older or externally edited saves can have an unreadable custom name.
        }
    }
    return text("unnamed").replace("%s", id.slice(0, 8));
}

function svg(tag, attributes = {}) {
    const element = document.createElementNS("http://www.w3.org/2000/svg", tag);
    for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, value);
    return element;
}

function distance(metres) {
    if (!Number.isFinite(metres)) return "—";
    return Math.abs(metres) >= 1000 ? number.format(metres / 1000) + " km" : number.format(metres) + " m";
}

function duration(seconds) {
    if (!Number.isFinite(seconds)) return "—";
    const whole = Math.max(0, Math.floor(seconds));
    const twoDigits = value => String(value).padStart(2, "0");
    return Math.floor(whole / 3600) + ":" + twoDigits(Math.floor(whole / 60) % 60) + ":" + twoDigits(whole % 60);
}

function liveSeconds() {
    return clock.seconds + Math.max(0, performance.now() - clock.received) * clock.rate / 1000;
}

function status(state) {
    $("connection").dataset.state = state;
    $("connection").textContent = text(state);
}

function syncClock(sample) {
    clock = { ...sample, received: performance.now() };
    timeline.disabled = false;
}

function updateFocusOptions() {
    const current = focus.value;
    focus.replaceChildren(new Option(text("system"), ""));
    for (const body of bodies.values()) focus.add(new Option(bodyName(body.id), body.id));
    if (bodies.has(current)) focus.value = current;
}

function selectShip(id) {
    selected = id;
    $("selected").hidden = !selected;
    for (const [key, ship] of ships) {
        const active = key === id;
        ship.button.setAttribute("aria-pressed", active);
        ship.graphic?.marker.classList.toggle("active", active);
        ship.graphic?.path?.classList.toggle("active", active);
    }
    if (id) updateDetails(ships.get(id));
}

function updateShips(updated, removed, redraw = false) {
    for (const id of removed) {
        const ship = ships.get(id);
        if (!ship) continue;
        ship.button.parentElement.remove();
        ships.delete(id);
        redraw = true;
    }
    for (const [id, data] of Object.entries(updated)) {
        let ship = ships.get(id);
        if (!ship) {
            const row = document.createElement("li");
            const button = document.createElement("button");
            button.type = "button";
            button.dataset.ship = id;
            button.addEventListener("click", () => selectShip(id));
            row.append(button);
            $("ships").append(row);
            ship = { button };
            ships.set(id, ship);
            redraw = true;
        }
        redraw ||= Boolean(ship.data?.orbit || data.orbit);
        ship.data = data;
        const label = shipName(id, data.name);
        if (label !== ship.name) {
            ship.name = label;
            ship.button.textContent = label;
            redraw = true;
        }
    }
    $("empty").hidden = ships.size > 0;
    if (!ships.has(selected)) selected = ships.keys().next().value ?? null;
    if (redraw) {
        layoutMap();
    } else {
        selectShip(selected);
    }
}

function openStream() {
    const stream = new EventSource("events");
    stream.addEventListener("snapshot", event => {
        const snapshot = JSON.parse(event.data);
        connected = true;
        status("connected");
        syncClock(snapshot.clock);
        bodies.clear();
        for (const body of snapshot.bodies) bodies.set(body.id, body);
        updateFocusOptions();
        const removed = [...ships.keys()].filter(id => !Object.hasOwn(snapshot.ships, id));
        updateShips(snapshot.ships, removed, true);
    });
    stream.addEventListener("delta", event => {
        const delta = JSON.parse(event.data);
        syncClock(delta.clock);
        updateShips(delta.updated, delta.removed);
    });
    stream.addEventListener("error", () => {
        if (connected && clock) clock = { seconds: liveSeconds(), rate: 0, received: performance.now() };
        connected = false;
        status("disconnected");
        if (stream.readyState === EventSource.CLOSED) setTimeout(openStream, 3000);
    });
}

function pathFor(orbit, range, className) {
    // Clip very eccentric and open conics in orbital space before projecting them.
    const limit = Math.max(range, periapsis(orbit) * 1.25);
    const angle = apoapsis(orbit) <= limit ? Math.PI : Math.acos(Math.min(1,
        (orbit.semiLatusRectum / limit - 1) / orbit.eccentricity));
    const points = [];
    for (let i = 0; i <= 160; i++) {
        const [x, y] = pointOnOrbit(orbit, angle * (i / 80 - 1));
        points.push(x * frame.scale + "," + -y * frame.scale);
    }
    return svg("polyline", { class: className, points: points.join(" ") });
}

function layoutMap() {
    const { width, height } = map.getBoundingClientRect();
    const focused = bodies.get(focus.value);
    const root = bodies.values().next().value;
    let extent = (focused ?? root)?.radius * 2 || 1;
    if (!focused) {
        for (const body of bodies.values()) {
            if (body.orbit) extent = Math.max(extent, (apoapsis(body.orbit) + body.soi) * 1.08);
        }
    }
    const scale = Math.max(1, Math.min(width, height) / 2 - 20) * zoom / extent;
    frame = { width, height, scale };
    $("scale").textContent = distance(64 / scale);
    map.setAttribute("viewBox", [0, 0, width, height].join(" "));
    const paths = svg("g");
    const disks = svg("g");
    const marks = svg("g");
    const range = Math.hypot(width, height) / scale;
    for (const body of bodies.values()) {
        body.path = body.orbit ? pathFor(body.orbit, range, "orbit") : null;
        if (body.path) paths.append(body.path);
        const radius = Math.max(3, body.radius * scale);
        body.marker = svg("g");
        if (body.soi) {
            const soi = svg("circle", { r: body.soi * scale, class: "soi" });
            const tooltip = svg("title");
            tooltip.textContent = text("soi") + " · " + distance(body.soi);
            soi.append(tooltip);
            body.marker.append(soi);
        }
        body.marker.append(svg("circle", { r: radius, class: body.orbit ? "body satellite" : "body" }));
        const label = svg("text", { class: "map-label", "text-anchor": "middle", y: -radius - 7 });
        label.textContent = bodyName(body.id);
        body.marker.append(label);
        disks.append(body.marker);
    }
    for (const [id, ship] of ships) {
        const orbit = ship.data.orbit;
        const path = orbit ? pathFor(orbit, range, "orbit ship-orbit") : null;
        if (path) {
            path.dataset.ship = id;
            paths.append(path);
        }
        const marker = orbit ? svg("circle", { class: "ship-marker", r: 5 })
            : svg("rect", { class: "ship-marker", width: 8, height: 8, x: -4, y: -4, rx: 1 });
        marker.dataset.ship = id;
        const title = svg("title");
        title.textContent = ship.name;
        marker.append(title);
        marker.addEventListener("click", () => selectShip(id));
        marks.append(marker);
        const node = ship.data.maneuver ? svg("path", {
            d: "M 0 -6 L 6 0 L 0 6 L -6 0 Z", class: "node-marker"
        }) : null;
        if (node) {
            const tooltip = svg("title");
            tooltip.textContent = text("maneuver") + " · " + duration(ship.data.maneuver.seconds);
            node.append(tooltip);
            marks.append(node);
        }
        ship.graphic = { path, marker, node };
    }
    map.replaceChildren(paths, disks, marks);
    selectShip(selected);
}

function add(a, b) {
    return [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
}

function drawMap(seconds) {
    const positions = new Map();
    for (const body of bodies.values()) {
        const pos = body.orbit ? add(positions.get(body.center),
            positionAt(body.orbit, bodies.get(body.center).mu, seconds)) : origin;
        positions.set(body.id, pos);
    }
    const [x0, y0] = positions.get(focus.value) ?? origin;
    const place = (element, [x, y]) => {
        const screenX = frame.width / 2 + (x - x0) * frame.scale;
        const screenY = frame.height / 2 - (y - y0) * frame.scale;
        element.setAttribute("transform", "translate(" + screenX + " " + screenY + ")");
    };
    for (const body of bodies.values()) {
        place(body.marker, positions.get(body.id));
        if (body.path) place(body.path, positions.get(body.center));
    }
    for (const [id, ship] of ships) {
        const { data, graphic } = ship;
        const center = positions.get(data.center);
        const body = bodies.get(data.center);
        const position = data.orbit ? positionAt(data.orbit, body.mu, seconds)
            : [data.position.x, data.position.y, data.position.z];
        if (graphic.path) place(graphic.path, center);
        place(graphic.marker, add(center, position));
        if (graphic.node) {
            place(graphic.node, add(center, positionAt(data.orbit, body.mu, data.maneuver.seconds)));
        }
        if (id === selected) {
            $("altitude").textContent = distance(data.orbit
                ? Math.hypot(...position) - body.radius : data.position.altitude);
        }
    }
}

function updateDetails(ship) {
    const { orbit, maneuver } = ship.data;
    const body = bodies.get(ship.data.center);
    $("ship-name").textContent = ship.name;
    $("center").textContent = bodyName(body.id);
    $("periapsis").textContent = orbit ? distance(periapsis(orbit) - body.radius) : "—";
    $("apoapsis").textContent = orbit ? distance(apoapsis(orbit) - body.radius) : "—";
    $("period").textContent = orbit ? duration(period(orbit, body.mu)) : "—";
    $("maneuver").hidden = !maneuver;
    if (maneuver) {
        $("maneuver-time").textContent = duration(maneuver.seconds);
        for (const direction of ["prograde", "normal", "radial"]) {
            $(direction).textContent = number.format(maneuver[direction]) + " m/s";
        }
    }
}

function animate() {
    if (clock) {
        const offset = Number(timeline.value);
        const seconds = previewAnchor == null ? liveSeconds() : previewAnchor + offset;
        $("clock").textContent = duration(seconds) + (offset > 0 ? " (+" + duration(offset) + ")"
            : connected ? " · " + number.format(clock.rate) + "×" : "");
        if (frame) drawMap(seconds);
    }
    requestAnimationFrame(animate);
}

timeline.addEventListener("input", () => {
    if (Number(timeline.value) === 0) {
        previewAnchor = null;
    } else {
        previewAnchor ??= liveSeconds();
    }
    $("live").disabled = previewAnchor == null;
});
$("live").addEventListener("click", () => {
    timeline.value = 0;
    previewAnchor = null;
    $("live").disabled = true;
});
focus.addEventListener("change", () => {
    zoom = 1;
    layoutMap();
});
function adjustZoom(factor) {
    zoom = Math.min(32, Math.max(0.25, zoom * factor));
    layoutMap();
}
$("zoom-in").addEventListener("click", () => adjustZoom(1.45));
$("zoom-out").addEventListener("click", () => adjustZoom(1 / 1.45));
window.addEventListener("resize", layoutMap);

status("connecting");
openStream();
requestAnimationFrame(animate);
