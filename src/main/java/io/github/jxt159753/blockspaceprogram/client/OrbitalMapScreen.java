package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion;
import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.orbit.ManeuverNode;
import io.github.jxt159753.blockspaceprogram.orbit.Orbit;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalNetworking;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalPlanner;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData.ManeuverResult;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalShipView;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class OrbitalMapScreen extends Screen {
    private static final int WHITE = 0xffe6f0ff;
    private static final int MUTED = 0xff9fb4cc;
    private static final int BODY = 0xff80a8d7;
    private static final int SHIP = 0xff68d9ec;
    private static final int TARGET = 0xffc2a2ff;
    private static final int PLAN = 0xffffc677;
    private static final int AXIS_TOP = 148;
    private static final int AXIS_HEIGHT = 12;
    private static final Direction.Axis[] AXES = Direction.Axis.values();
    private static final String[] AXIS_NAMES = { "prograde", "normal", "radial" };

    private final Map<Orbit, List<Vec3>> orbitLines = new HashMap<>();
    private Map<UUID, OrbitalShipView> lastShips = Map.of();
    private double lastForecast = Double.NEGATIVE_INFINITY;
    private OrbitalMapViewport viewport;
    private int panelLeft;
    private int panelWidth;
    private Button shipButton;
    private Button targetButton;
    private Button hohmannButton;
    private Button clearButton;
    private Button executeButton;
    private Button cancelButton;
    @Nullable private UUID selectedShip;
    @Nullable private Target target;
    @Nullable private ManeuverNode draft;
    @Nullable private OrbitalMapForecast forecast;
    @Nullable private ManeuverResult feedback;
    private boolean awaitingServer;
    private boolean rotating;
    private int draggingAxis = -1;

    OrbitalMapScreen() {
        super(text("title"));
    }

    @Override
    protected void init() {
        panelWidth = Math.clamp(width / 3, 139, 210);
        panelLeft = width - panelWidth - 8;
        viewport = new OrbitalMapViewport(8, 28, panelLeft - 6, height - 31);
        shipButton = addButton("ship_button", 0, 31, this::cycleShip);
        targetButton = addButton("target_button", 1, 31, this::cycleTarget);
        hohmannButton = addButton("hohmann_button", 0, 198, this::planTransfer);
        hohmannButton.setTooltip(Tooltip.create(text("hohmann_tip")));
        clearButton = addButton("clear_button", 1, 198, () -> setDraft(null));
        executeButton = addButton("execute_button", 0, 218, () -> send(Optional.ofNullable(draft)));
        cancelButton = addButton("cancel_button", 1, 218, () -> send(Optional.empty()));
        refresh(true);
    }

    private Button addButton(String label, int column, int y, Runnable action) {
        int buttonWidth = (panelWidth - 15) / 2;
        return addRenderableWidget(Button.builder(text(label), button -> action.run())
                .bounds(panelLeft + 6 + column * (buttonWidth + 3), y, buttonWidth, 17).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void tick() {
        refresh(false);
    }

    private void refresh(boolean changed) {
        var ships = ClientOrbitalState.ships();
        if (selectedShip == null || !ships.containsKey(selectedShip)) {
            selectShip(sortedShips().stream().findFirst().orElse(null));
        }
        if (target != null && target.ship != null && !ships.containsKey(target.ship)) {
            target = null;
        }
        var selected = selectedView();
        var armed = selected == null ? null : selected.maneuver().orElse(null);
        if (armed != null) {
            draft = null;
        }
        double now = ClientOrbitalState.orbitSeconds(0);
        boolean shipsChanged = !lastShips.equals(ships);
        if (changed || shipsChanged || now < lastForecast || now >= lastForecast + 1) {
            lastForecast = now;
            if (shipsChanged) {
                lastShips = Map.copyOf(ships);
            }
            var system = ClientOrbitalState.system();
            refreshOrbitLines(system, now);
            var targetBody = target == null || target.body == null ? null : system.body(target.body);
            var targetShip = target == null ? null : ships.get(target.ship);
            forecast = selected == null ? null : OrbitalMapForecast.calculate(system, selected.orbit(),
                    armed == null ? draft : armed, targetBody, targetShip, now);
        }
        shipButton.active = ships.size() > 1;
        targetButton.active = selected != null;
        var window = forecast == null ? null : forecast.window();
        hohmannButton.active = armed == null && window != null
                && window.node().seconds() <= now + ManeuverNode.MAX_LEAD_SECONDS
                && window.node().valid();
        clearButton.active = draft != null;
        double deltaV = draft == null ? 0 : draft.deltaV().length();
        var budget = burnEstimate(selected, draft);
        executeButton.active = !awaitingServer && armed == null && draft != null && draft.seconds() > now
                && forecast != null && forecast.preview() != null && budget != null && budget.supplies(deltaV);
        cancelButton.active = !awaitingServer && armed != null;
    }

    private void refreshOrbitLines(OrbitalSystem system, double now) {
        var visible = new HashSet<Orbit>();
        for (CelestialBody body : system.bodies()) {
            if (body.orbit() != null) {
                visible.add(body.orbit());
            }
        }
        lastShips.values().forEach(ship -> visible.add(ship.orbit()));
        orbitLines.keySet().retainAll(visible);
        for (Orbit orbit : visible) {
            double period = orbit.elements().period(system.body(orbit.center()).gravitationalParameter());
            if (!orbitLines.containsKey(orbit) || !Double.isFinite(period)) {
                double start = Double.isFinite(period) ? orbit.elements().epochSeconds() : now;
                double end = start + (Double.isFinite(period) ? period : OrbitalPlanner.PREDICTION_SECONDS);
                var path = OrbitalMapForecast.Path.sample(seconds -> system.relativeState(orbit, seconds), start, end);
                orbitLines.put(orbit, path.positions());
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        double now = ClientOrbitalState.orbitSeconds(partialTick);
        graphics.fill(0, 0, width, height, 0xff0a111d);
        graphics.fill(0, 0, width, 23, 0xff1b2b42);
        graphics.drawString(font, title, 8, 8, WHITE);
        var clock = text("clock", duration(now));
        graphics.drawString(font, clock, width - 8 - font.width(clock), 8, MUTED);

        viewport.background(graphics);
        drawMap(graphics, frame(now), now);
        graphics.fill(panelLeft, 28, panelLeft + panelWidth, height - 3, 0xff142033);
        graphics.renderOutline(panelLeft, 28, panelWidth, height - 31, 0xff32455d);
        drawPanel(graphics, now);
        graphics.drawString(font, text("map_controls"), 8, height - 24, MUTED);
        graphics.drawString(font, text("view_controls"), 8, height - 12, MUTED);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private OrbitalMapViewport.Frame frame(double seconds) {
        var system = ClientOrbitalState.system();
        var selected = selectedView();
        ResourceLocation center = selected == null ? OrbitalSystem.MOTHER_PLANET : selected.orbit().center();
        double extent = system.sphereOfInfluence(center);
        if (!Double.isFinite(extent)) {
            extent = system.body(center).radius() * 2;
            for (CelestialBody body : system.bodies()) {
                if (body.orbit() != null && center.equals(body.orbit().center())) {
                    extent = Math.max(extent, body.orbit().elements().apoapsis() * 1.13);
                }
            }
        }
        if (selected != null) {
            extent = Math.max(extent, system.relativeState(selected.orbit(), seconds).position().length() * 1.08);
            double apoapsis = selected.orbit().elements().apoapsis();
            if (Double.isFinite(apoapsis)) {
                extent = Math.max(extent, apoapsis * 1.08);
            }
        }
        return viewport.frame(system.bodyState(center, seconds).position(), extent);
    }

    private void drawMap(GuiGraphics graphics, OrbitalMapViewport.Frame frame, double now) {
        var system = ClientOrbitalState.system();
        graphics.enableScissor(viewport.left + 1, viewport.top + 1, viewport.right - 1, viewport.bottom - 1);
        var ink = new OrbitalMapViewport.Ink(graphics, frame);
        for (CelestialBody body : system.bodies()) {
            var orbit = body.orbit();
            if (orbit != null) {
                drawOrbit(ink, system, orbit, now, new Target(body.id(), null).equals(target) ? TARGET : 0xff4b6589);
            }
        }
        for (var entry : lastShips.entrySet()) {
            if (!entry.getKey().equals(selectedShip)) {
                drawOrbit(ink, system, entry.getValue().orbit(), now,
                        new Target(null, entry.getKey()).equals(target) ? TARGET : 0xff395977);
            }
        }
        if (forecast != null) {
            ink.path(forecast.coast().positions(), Vec3.ZERO, SHIP, 1.5f, false);
            if (forecast.preview() != null) {
                ink.path(forecast.preview().positions(), Vec3.ZERO, PLAN, 1.6f, true);
            }
        }

        for (CelestialBody body : system.bodies()) {
            var point = frame.project(system.bodyState(body.id(), now).position());
            float radius = (float) Math.clamp(body.radius() * frame.scale(), 3.5, 512);
            ink.circle(point, radius, 0xff233d59, true);
            ink.circle(point, radius, new Target(body.id(), null).equals(target) ? TARGET : BODY, false);
        }
        for (var entry : lastShips.entrySet()) {
            var id = entry.getKey();
            var point = frame.project(system.absoluteState(entry.getValue().orbit(), now).position());
            boolean selected = id.equals(selectedShip);
            boolean targeted = new Target(null, id).equals(target);
            ink.diamond(point, selected || targeted ? 4 : 2.5f, selected ? SHIP : targeted ? TARGET : BODY);
        }
        if (forecast != null && forecast.nodePosition() != null) {
            var point = frame.project(forecast.nodePosition());
            ink.circle(point, 6.5f, PLAN, false);
            ink.diamond(point, 3, PLAN);
        }
        if (forecast != null && forecast.approach() != null) {
            var approach = forecast.approach();
            var closest = frame.project(approach.shipPosition());
            var contact = frame.project(approach.targetPosition());
            ink.line(closest, contact, TARGET, 1);
            ink.circle(contact, 6.5f, TARGET, false);
            ink.diamond(closest, 3.5f, WHITE);
        }
        graphics.flush();
        for (CelestialBody body : system.bodies()) {
            var point = frame.project(system.bodyState(body.id(), now).position());
            drawLabel(graphics, bodyName(body.id()), point, 6, BODY);
            var orbit = body.orbit();
            if (orbit != null && orbit.elements().inclination() > 0) {
                drawOrbitalNodes(graphics, frame, orbit, system.bodyState(orbit.center(), now).position());
            }
        }
        if (forecast != null && forecast.approach() != null) {
            String label = forecast.surfaceContact() ? "surface_mark" : "closest_mark";
            drawLabel(graphics, text(label), frame.project(forecast.approach().shipPosition()), -14, TARGET);
        }
        int scaleY = viewport.bottom - 9;
        graphics.hLine(viewport.left + 7, viewport.left + 39, scaleY, MUTED);
        graphics.drawString(font, distance(32 / frame.scale()), viewport.left + 44, scaleY - 4, MUTED);
        if (feedback != null) {
            graphics.drawString(font, text("result." + feedback.name().toLowerCase(Locale.ROOT)),
                    viewport.left + 7, viewport.top + 7, PLAN);
        }
        graphics.disableScissor();
    }

    private void drawOrbit(OrbitalMapViewport.Ink ink, OrbitalSystem system, Orbit orbit, double now, int color) {
        var line = orbitLines.get(orbit);
        if (line != null) {
            ink.path(line, system.bodyState(orbit.center(), now).position(), color, 1, false);
        }
    }

    private void drawOrbitalNodes(GuiGraphics graphics, OrbitalMapViewport.Frame frame, Orbit orbit, Vec3 center) {
        var elements = orbit.elements();
        double longitude = elements.longitudeOfAscendingNode();
        var direction = new Vec3(Math.cos(longitude), Math.sin(longitude), 0);
        // The ascending and descending nodes have true anomalies -ω and π - ω.
        double radialFactor = elements.eccentricity() * Math.cos(elements.argumentOfPeriapsis());
        var ascending = frame.project(center.add(direction.scale(elements.semiLatusRectum() / (1 + radialFactor))));
        var descending = frame.project(center.add(direction.scale(-elements.semiLatusRectum() / (1 - radialFactor))));
        drawOrbitalNode(graphics, "ascending_node", ascending);
        drawOrbitalNode(graphics, "descending_node", descending);
    }

    private void drawOrbitalNode(GuiGraphics graphics, String label, OrbitalMapViewport.Point point) {
        int x = Math.round(point.x());
        int y = Math.round(point.y());
        graphics.fill(x - 1, y - 1, x + 2, y + 2, MUTED);
        drawLabel(graphics, text(label), point, 5, MUTED);
    }

    private void drawLabel(GuiGraphics graphics, Component label, OrbitalMapViewport.Point point, int yOffset, int color) {
        if (viewport.contains(point.x(), point.y())) {
            int x = Math.min(Math.round(point.x()) + 6, viewport.right - font.width(label) - 3);
            graphics.drawString(font, label, x, Math.round(point.y()) + yOffset, color);
        }
    }

    private void drawPanel(GuiGraphics graphics, double now) {
        var system = ClientOrbitalState.system();
        var selected = selectedView();
        if (selected == null) {
            panelLine(graphics, text("empty"), 54, MUTED);
            return;
        }
        var orbit = selected.orbit();
        var body = system.body(orbit.center());
        var elements = orbit.elements();
        double altitude = system.relativeState(orbit, now).position().length() - body.radius();
        panelLine(graphics, text("selected", shortId(selectedShip), bodyName(body.id())), 52, SHIP);
        panelPair(graphics, text("periapsis", distance(elements.periapsis() - body.radius())),
                text("apoapsis", distance(elements.apoapsis() - body.radius())), 64);
        panelPair(graphics, text("altitude", distance(altitude)),
                text("period", duration(elements.period(body.gravitationalParameter()))), 76);
        panelLine(graphics, target == null ? text("no_target") : text("target", target.label()), 88, TARGET);
        if (forecast != null && forecast.window() != null) {
            var window = forecast.window();
            panelLine(graphics, text("phase", degrees(window.currentPhase()), degrees(window.departurePhase())), 100, TARGET);
        }
        if (forecast != null && forecast.approach() != null) {
            var approach = forecast.approach();
            var label = "closest";
            if (!forecast.surfaceContact() && target != null && target.body != null && system.body(target.body).orbit() != null
                    && approach.distance() < system.sphereOfInfluence(target.body)) {
                label = "encounter";
            }
            panelLine(graphics, text(label, distance(approach.distance()), number(approach.relativeSpeed(), 0)), 112, TARGET);
            panelLine(graphics, text(forecast.surfaceContact() ? "surface_time" : "approach_time",
                    duration(approach.seconds() - now)), 124, MUTED);
        }

        var armed = selected.maneuver().orElse(null);
        var node = armed == null ? draft : armed;
        if (node == null) {
            var window = forecast == null ? null : forecast.window();
            panelLine(graphics, window == null ? text("place_node")
                    : text("window", duration(window.node().seconds() - now)), 137, MUTED);
        } else {
            var label = armed != null ? "armed" : node.seconds() < now ? "expired" : "node";
            panelLine(graphics, text(label, duration(node.seconds() - now), number(node.deltaV().length(), 1)), 137, PLAN);
            for (int i = 0; i < AXES.length; i++) {
                int y = AXIS_TOP + i * AXIS_HEIGHT;
                if (armed == null) {
                    graphics.fill(panelLeft + 5, y, panelLeft + panelWidth - 5, y + AXIS_HEIGHT - 1,
                            draggingAxis == i ? 0xff4b412f : 0xff293449);
                }
                double deltaV = node.deltaV().get(AXES[i]);
                panelLine(graphics, text(AXIS_NAMES[i], String.format(Locale.ROOT, "%+.1f", deltaV)), y + 2, WHITE);
            }
        }
        var budget = burnEstimate(selected, node);
        if (node != null && node.deltaV().lengthSqr() > 0) {
            if (budget != null && budget.supplies(node.deltaV().length())) {
                panelLine(graphics, text("burn", number(budget.seconds(), 2), number(budget.fuel(), 1)), 186, PLAN);
            } else {
                panelLine(graphics, text("result.insufficient_fuel"), 186, PLAN);
            }
        } else {
            selected.propulsion().ifPresent(propulsion ->
                    panelLine(graphics, text("fuel", number(propulsion.fuel(), 1)), 186, MUTED));
        }
    }

    private void panelLine(GuiGraphics graphics, Component value, int y, int color) {
        drawText(graphics, value, panelLeft + 7, y, panelWidth - 14, color);
    }

    private void panelPair(GuiGraphics graphics, Component first, Component second, int y) {
        int half = (panelWidth - 14) / 2;
        drawText(graphics, first, panelLeft + 7, y, half, WHITE);
        drawText(graphics, second, panelLeft + 7 + half, y, half, WHITE);
    }

    private void drawText(GuiGraphics graphics, Component value, int x, int y, int maxWidth, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value.getString(), maxWidth), x, y, color);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (viewport.contains(x, y)) {
            if (button == 1) {
                rotating = true;
                return true;
            }
            if (button == 0) {
                clickMap(x, y);
                return true;
            }
        }
        if (button == 0 && draft != null && x >= panelLeft && x < panelLeft + panelWidth
                && y >= AXIS_TOP && y < AXIS_TOP + AXES.length * AXIS_HEIGHT) {
            draggingAxis = (int) (y - AXIS_TOP) / AXIS_HEIGHT;
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    private void clickMap(double x, double y) {
        double now = ClientOrbitalState.orbitSeconds(0);
        var frame = frame(now);
        Target hit = pickContact(frame, x, y, now);
        if (hit != null) {
            if (hit.body != null || hasShiftDown()) {
                if (!selectedShipEquals(hit.ship)) {
                    target = hit.equals(target) ? null : hit;
                }
            } else {
                selectShip(hit.ship);
            }
            refresh(true);
        } else if (forecast != null && selectedView() != null && selectedView().maneuver().isEmpty()) {
            double time = forecast.coast().pick(frame, x, y, now + OrbitalPlanner.NODE_LEAD_SECONDS);
            if (Double.isFinite(time)) {
                setDraft(new ManeuverNode(time, draft == null ? Vec3.ZERO : draft.deltaV()));
            }
        }
    }

    @Nullable
    private Target pickContact(OrbitalMapViewport.Frame frame, double x, double y, double now) {
        var system = ClientOrbitalState.system();
        double nearest = 36;
        Target hit = null;
        for (var entry : lastShips.entrySet()) {
            double distance = frame.project(system.absoluteState(entry.getValue().orbit(), now).position()).distanceSquared(x, y);
            if (distance < nearest) {
                nearest = distance;
                hit = new Target(null, entry.getKey());
            }
        }
        for (CelestialBody body : system.bodies()) {
            double distance = frame.project(system.bodyState(body.id(), now).position()).distanceSquared(x, y);
            if (distance < nearest) {
                nearest = distance;
                hit = new Target(body.id(), null);
            }
        }
        return hit;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 1 && rotating) {
            viewport.rotate(dx, dy);
            return true;
        }
        if (button == 0 && draggingAxis >= 0 && draft != null) {
            var axis = AXES[draggingAxis];
            double sensitivity = hasShiftDown() ? 0.1 : hasControlDown() ? 10 : 2;
            double value = Math.clamp(draft.deltaV().get(axis) + dx * sensitivity,
                    -ManeuverNode.MAX_COMPONENT, ManeuverNode.MAX_COMPONENT);
            setDraft(new ManeuverNode(draft.seconds(), draft.deltaV().with(axis, value)));
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 1 && rotating) {
            rotating = false;
            return true;
        }
        if (button == 0 && draggingAxis >= 0) {
            draggingAxis = -1;
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (viewport.contains(x, y)) {
            viewport.zoom(vertical);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (OrbitalMapClient.isMapKey(key, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private void cycleShip() {
        var ships = sortedShips();
        if (!ships.isEmpty()) {
            selectShip(ships.get((ships.indexOf(selectedShip) + 1) % ships.size()));
            refresh(true);
        }
    }

    private void cycleTarget() {
        var targets = new ArrayList<Target>();
        for (CelestialBody body : ClientOrbitalState.system().bodies()) {
            targets.add(new Target(body.id(), null));
        }
        for (UUID id : sortedShips()) {
            if (!id.equals(selectedShip)) {
                targets.add(new Target(null, id));
            }
        }
        int next = targets.indexOf(target) + 1;
        target = next == targets.size() ? null : targets.get(next);
        refresh(true);
    }

    private void planTransfer() {
        if (forecast != null && forecast.window() != null) {
            setDraft(forecast.window().node());
        }
    }

    private void selectShip(@Nullable UUID id) {
        if (id != null ? id.equals(selectedShip) : selectedShip == null) {
            return;
        }
        selectedShip = id;
        if (target != null && selectedShipEquals(target.ship)) {
            target = null;
        }
        draft = null;
        feedback = null;
        awaitingServer = false;
        lastForecast = Double.NEGATIVE_INFINITY;
    }

    private boolean selectedShipEquals(@Nullable UUID id) {
        return id != null && id.equals(selectedShip);
    }

    private void setDraft(@Nullable ManeuverNode next) {
        draft = next;
        feedback = null;
        refresh(true);
    }

    private void send(Optional<ManeuverNode> node) {
        feedback = null;
        awaitingServer = true;
        OrbitalNetworking.sendManeuver(selectedShip, node);
        refresh(false);
    }

    void receiveResult(UUID id, ManeuverResult result) {
        if (selectedShipEquals(id)) {
            awaitingServer = false;
            feedback = result;
            refresh(false);
        }
    }

    @Nullable
    private OrbitalShipView selectedView() {
        return ClientOrbitalState.ships().get(selectedShip);
    }

    @Nullable
    private static VacuumPropulsion.Burn burnEstimate(@Nullable OrbitalShipView selected, @Nullable ManeuverNode node) {
        if (selected == null || node == null) {
            return null;
        }
        return selected.propulsion().map(propulsion -> propulsion.estimate(node.deltaV().length())).orElse(null);
    }

    private static List<UUID> sortedShips() {
        return ClientOrbitalState.ships().keySet().stream().sorted().toList();
    }

    private static Component bodyName(ResourceLocation id) {
        return text("body." + id.getPath());
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("map.blockspaceprogram." + key, args);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static String degrees(double radians) {
        return number(Math.toDegrees(radians), 0) + "°";
    }

    private static String distance(double meters) {
        if (!Double.isFinite(meters)) {
            return "∞";
        }
        return Math.abs(meters) < 1_000 ? number(meters, 0) + " m" : number(meters / 1_000, 1) + " km";
    }

    private static String duration(double seconds) {
        if (!Double.isFinite(seconds)) {
            return "∞";
        }
        long time = (long) Math.ceil(Math.max(0, seconds));
        return time >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", time / 3600, time / 60 % 60, time % 60)
                : String.format(Locale.ROOT, "%02d:%02d", time / 60, time % 60);
    }

    private record Target(@Nullable ResourceLocation body, @Nullable UUID ship) {
        Component label() {
            return body == null ? text("vessel", shortId(ship)) : bodyName(body);
        }
    }
}
