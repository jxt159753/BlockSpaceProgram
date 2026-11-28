package io.github.jxt159753.blockspaceprogram.compat.computercraft;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.NotAttachedException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.FlightTerminalBlockEntity;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.AttitudeMode;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.ThrottleMode;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightEvent;
import io.github.jxt159753.blockspaceprogram.orbit.ManeuverNode;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalFlightControl;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalFlightControl.OrbitTelemetry;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData.ManeuverResult;
import io.github.jxt159753.blockspaceprogram.orbit.ShipOrbitEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/** ComputerCraft adapter for a ground terminal. All world access runs on the server thread. */
public final class FlightPeripheral implements IPeripheral {
    private static final Set<FlightPeripheral> ATTACHED = ConcurrentHashMap.newKeySet();
    private final FlightTerminalBlockEntity terminal;
    private final Map<IComputerAccess, Attachment> computers = new ConcurrentHashMap<>();

    private FlightPeripheral(FlightTerminalBlockEntity terminal) {
        this.terminal = terminal;
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterCapabilitiesEvent event) -> event.registerBlockEntity(PeripheralCapability.get(),
                BlockSpaceProgram.FLIGHT_TERMINAL_ENTITY.get(), (terminal, side) -> new FlightPeripheral(terminal)));
        NeoForge.EVENT_BUS.addListener(FlightPeripheral::onFlightEvent);
        NeoForge.EVENT_BUS.addListener(FlightPeripheral::onOrbitEvent);
    }

    @Override
    public String getType() {
        return "bsp_flight_terminal";
    }

    @Override
    public Object getTarget() {
        return terminal;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof FlightPeripheral peripheral && terminal == peripheral.terminal;
    }

    @Override
    public synchronized void attach(IComputerAccess computer) {
        computers.putIfAbsent(computer, new Attachment());
        ATTACHED.add(this);
    }

    @Override
    public synchronized void detach(IComputerAccess computer) {
        computers.remove(computer);
        if (computers.isEmpty()) {
            ATTACHED.remove(this);
        }
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getTelemetry() throws LuaException {
        var telemetry = orbitalControl().telemetry();
        if (telemetry == null) {
            throw new LuaException("No loaded ship is bound to this flight terminal");
        }
        var data = telemetry.flight();
        var result = new LinkedHashMap<String, Object>();
        result.put("ship", data.shipId().toString());
        result.put("dimension", data.dimension().toString());
        result.put("position", vector(data.position()));
        result.put("velocity", vector(data.velocity()));
        result.put("altitude", data.altitude());
        result.put("verticalSpeed", data.verticalSpeed());
        var attitude = data.attitude();
        result.put("attitude", Map.of("pitch", attitude.pitch(), "yaw", attitude.yaw(), "roll", attitude.roll()));
        result.put("angularVelocity", vector(data.angularVelocity()));
        result.put("mass", data.mass());
        result.put("fuel", data.fuel());
        result.put("thrust", data.thrust());
        result.put("availableThrust", data.availableThrust());
        result.put("gravity", data.gravity());
        result.put("thrustToWeight", data.thrustToWeight());
        result.put("throttle", data.throttle());
        result.put("stage", data.stage() + 1);
        result.put("attitudeMode", data.attitudeMode().id());
        result.put("throttleMode", data.throttleMode().id());
        result.put("axes", Map.of("pitch", data.axes().x, "yaw", data.axes().y, "roll", data.axes().z));
        result.put("landed", data.landed());
        var planet = data.planet();
        if (planet != null) {
            result.put("planet", Map.of("seaLevel", planet.seaLevel(), "radius", planet.radius(),
                    "surfaceGravity", planet.surfaceGravity(), "seaLevelDensity", planet.seaLevelDensity(),
                    "atmosphereTop", planet.atmosphereTop(), "scaleHeight", planet.scaleHeight()));
        }
        if (telemetry.orbit() != null) {
            result.put("orbit", orbitTable(telemetry.orbit()));
        }
        return result;
    }

    @LuaFunction(mainThread = true)
    public final void setThrottle(IComputerAccess computer, IArguments args) throws LuaException {
        double throttle = args.getFiniteDouble(0);
        if (throttle < 0 || throttle > 1) {
            throw new LuaException("Throttle must be between 0 and 1");
        }
        commandFlight(computer).setThrottle(ThrottleMode.DIRECT, throttle);
    }

    @LuaFunction(mainThread = true)
    public final void setVerticalSpeed(IComputerAccess computer, IArguments args) throws LuaException {
        double target = args.getFiniteDouble(0);
        commandFlight(computer).setThrottle(ThrottleMode.VERTICAL_SPEED, target);
    }

    @LuaFunction(mainThread = true)
    public final void setThrustToWeight(IComputerAccess computer, IArguments args) throws LuaException {
        double target = args.getFiniteDouble(0);
        if (target < 0) {
            throw new LuaException("Thrust-to-weight ratio must be nonnegative");
        }
        commandFlight(computer).setThrottle(ThrottleMode.THRUST_TO_WEIGHT, target);
    }

    @LuaFunction(mainThread = true)
    public final void setAxes(IComputerAccess computer, IArguments args) throws LuaException {
        double pitch = axis(args, 0);
        double yaw = axis(args, 1);
        double roll = axis(args, 2);
        commandFlight(computer).setAxes(pitch, yaw, roll);
    }

    @LuaFunction(mainThread = true)
    public final void setAttitudeMode(IComputerAccess computer, String id) throws LuaException {
        AttitudeMode mode;
        try {
            mode = AttitudeMode.valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new LuaException("Unknown attitude mode: " + id);
        }
        commandFlight(computer).setAttitudeMode(mode);
    }

    @LuaFunction(mainThread = true)
    public final void holdAttitude(IComputerAccess computer, IArguments args) throws LuaException {
        double pitch = args.getFiniteDouble(0);
        double yaw = args.getFiniteDouble(1);
        double roll = args.getFiniteDouble(2);
        commandFlight(computer).holdAttitude(pitch, yaw, roll);
    }

    @LuaFunction(mainThread = true)
    public final boolean stage(IComputerAccess computer) throws LuaException {
        return commandFlight(computer).requestStage();
    }

    @LuaFunction(mainThread = true)
    public final boolean releaseControl(IComputerAccess computer) throws LuaException {
        var attachment = computers.get(computer);
        if (attachment == null || attachment.connection == null) {
            return false;
        }
        var flight = terminal.flightControl();
        return flight == null ? orbitalControl().releaseOnRails(attachment.connection)
                : flight.releaseRemote(attachment.connection);
    }

    @LuaFunction(mainThread = true)
    public final void addManeuverNode(IArguments args) throws LuaException {
        double time = args.getFiniteDouble(0);
        var deltaV = new Vec3(args.getFiniteDouble(1), args.getFiniteDouble(2), args.getFiniteDouble(3));
        checkManeuver(orbitalControl().addManeuverNode(new ManeuverNode(time, deltaV)));
    }

    @LuaFunction(mainThread = true)
    public final boolean deleteManeuverNode() throws LuaException {
        var result = orbitalControl().deleteManeuverNode();
        if (result == ManeuverResult.NO_NODE) {
            return false;
        }
        checkManeuver(result);
        return true;
    }

    @LuaFunction(mainThread = true)
    public final void executeManeuverNode() throws LuaException {
        checkManeuver(orbitalControl().executeManeuverNode());
    }

    @LuaFunction(mainThread = true)
    public final void warpTo(IArguments args) throws LuaException {
        switch (orbitalControl().warpTo(args.getFiniteDouble(0))) {
            case STARTED -> { }
            case MULTIPLAYER -> throw new LuaException("Time warp is only available in a private singleplayer world");
            case FORBIDDEN -> throw new LuaException("Only the singleplayer owner may request time warp");
            case NOT_IN_ORBIT -> throw new LuaException("The bound ship is not in orbit");
            case NOT_COASTING -> throw new LuaException("Time warp requires coasting in orbit");
            case INVALID_TIME -> throw new LuaException("Expected a future orbital time in seconds");
            case BUSY -> throw new LuaException("The orbital region is running a benchmark");
        }
    }

    private static void checkManeuver(ManeuverResult result) throws LuaException {
        switch (result) {
            case PLANNED, QUEUED, CANCELED -> { }
            case MISSING_SHIP -> throw new LuaException("The bound ship must be on rails to plan a maneuver");
            case FORBIDDEN -> throw new LuaException("This ship is unavailable for maneuver commands");
            case NO_NODE -> throw new LuaException("No maneuver node has been added");
            case ALREADY_QUEUED -> throw new LuaException("A maneuver is already queued for this ship");
            case INVALID_NODE -> throw new LuaException("Invalid maneuver time or delta-v");
            case INSUFFICIENT_FUEL -> throw new LuaException("Insufficient fuel for the maneuver");
            case NO_THRUST -> throw new LuaException("Engine thrust cancels out");
        }
    }

    private ShipFlightControl commandFlight(IComputerAccess computer) throws LuaException {
        var flight = boundFlight();
        var attachment = computers.get(computer);
        if (attachment == null) {
            throw new LuaException("Flight controller detached");
        }
        int revision = terminal.bindingRevision();
        if (attachment.connection == null || attachment.connection.revision != revision) {
            attachment.connection = new Connection(computer, attachment, revision);
        }
        return switch (flight.claimRemote(attachment.connection)) {
            case CONNECTED -> flight;
            case BUSY -> throw new LuaException("Another computer controls this ship");
            case TICKET_LIMIT -> throw new LuaException("Remote flight load limit reached in this dimension");
        };
    }

    private ShipFlightControl boundFlight() throws LuaException {
        var flight = terminal.flightControl();
        if (flight == null) {
            throw new LuaException("No loaded ship is bound to this flight terminal");
        }
        return flight;
    }

    private OrbitalFlightControl orbitalControl() throws LuaException {
        var id = terminal.shipId();
        var owner = terminal.ownerId();
        if (terminal.isAvailable() && terminal.getLevel() instanceof ServerLevel level && id != null && owner != null) {
            return new OrbitalFlightControl(level.getServer(), id, owner);
        }
        throw new LuaException("No loaded ship is bound to this flight terminal");
    }

    private static double axis(IArguments args, int index) throws LuaException {
        double value = args.getFiniteDouble(index);
        if (value < -1 || value > 1) {
            throw new LuaException("Attitude axes must be between -1 and 1");
        }
        return value;
    }

    private static Map<String, Double> vector(Vec3 v) {
        return Map.of("x", v.x, "y", v.y, "z", v.z);
    }

    private static Map<String, Object> orbitTable(OrbitTelemetry reading) {
        var orbit = new LinkedHashMap<String, Object>();
        orbit.put("soi", reading.orbit().center().toString());
        orbit.put("seconds", reading.seconds());
        orbit.put("onRails", reading.onRails());
        orbit.put("position", vector(reading.state().position()));
        orbit.put("velocity", vector(reading.state().velocity()));
        orbit.put("periapsis", reading.periapsis());
        putFinite(orbit, "apoapsis", reading.apoapsis());
        putFinite(orbit, "period", reading.period());
        var elements = reading.orbit().elements();
        var conic = new LinkedHashMap<String, Double>();
        putFinite(conic, "semiMajorAxis", elements.semiMajorAxis());
        conic.put("semiLatusRectum", elements.semiLatusRectum());
        conic.put("eccentricity", elements.eccentricity());
        conic.put("inclination", elements.inclination());
        conic.put("longitudeOfAscendingNode", elements.longitudeOfAscendingNode());
        conic.put("argumentOfPeriapsis", elements.argumentOfPeriapsis());
        conic.put("meanAnomalyAtEpoch", elements.meanAnomalyAtEpoch());
        conic.put("epochSeconds", elements.epochSeconds());
        orbit.put("elements", conic);
        reading.maneuver().ifPresent(node -> orbit.put("maneuver", Map.of("seconds", node.seconds(),
                "prograde", node.deltaV().x, "normal", node.deltaV().y, "radial", node.deltaV().z, "queued", reading.queued())));
        return orbit;
    }

    private static void putFinite(Map<String, ? super Double> table, String key, double value) {
        if (Double.isFinite(value)) {
            table.put(key, value);
        }
    }

    private static void onFlightEvent(ShipFlightEvent event) {
        String name = switch (event.kind()) {
            case FUEL_DEPLETED -> "bsp_ship_fuel_depleted";
            case LANDED -> "bsp_ship_landed";
            case STAGE_CHANGED -> "bsp_ship_stage";
        };
        String shipId = event.ship().getUUID().toString();
        for (var peripheral : ATTACHED) {
            if (peripheral.terminal.isBoundTo(event.ship())) {
                peripheral.queueEvent(name, shipId, event.stage() + 1);
            }
        }
    }

    private static void onOrbitEvent(ShipOrbitEvent event) {
        String name = switch (event.kind()) {
            case ENTERED_ORBIT -> "bsp_ship_entered_orbit";
            case LEFT_ORBIT -> "bsp_ship_left_orbit";
            case SOI_CHANGED -> "bsp_ship_soi_changed";
        };
        var server = ServerLifecycleHooks.getCurrentServer();
        String shipId = event.shipId().toString();
        String center = event.center().toString();
        for (var peripheral : ATTACHED) {
            if (peripheral.terminal.getLevel() instanceof ServerLevel level && level.getServer() == server
                    && peripheral.terminal.isBoundTo(event.shipId(), event.ownerId())) {
                if (event.previousCenter() == null) {
                    peripheral.queueEvent(name, shipId, center);
                } else {
                    peripheral.queueEvent(name, shipId, event.previousCenter().toString(), center);
                }
            }
        }
    }

    private void queueEvent(String name, String shipId, Object... details) {
        var args = new Object[details.length + 2];
        args[1] = shipId;
        System.arraycopy(details, 0, args, 2, details.length);
        for (var computer : computers.keySet()) {
            try {
                args[0] = computer.getAttachmentName();
                computer.queueEvent(name, args.clone());
            } catch (NotAttachedException ignored) {
                // The computer detached while the event was queued.
            }
        }
    }

    private static final class Attachment {
        @Nullable
        private Connection connection;
    }

    private final class Connection implements BooleanSupplier {
        private final IComputerAccess computer;
        private final Attachment attachment;
        private final int revision;

        private Connection(IComputerAccess computer, Attachment attachment, int revision) {
            this.computer = computer;
            this.attachment = attachment;
            this.revision = revision;
        }

        @Override
        public boolean getAsBoolean() {
            return computers.get(computer) == attachment && terminal.bindingRevision() == revision && terminal.isAvailable();
        }
    }
}
