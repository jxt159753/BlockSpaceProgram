import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { positionAt } from "../../main/resources/assets/blockspaceprogram/web_preview/kepler.mjs";

const fixtures = JSON.parse(readFileSync(new URL("../resources/orbit/kepler-position-cases.json", import.meta.url), "utf8"));
for (const { name, mu, seconds, orbit, position } of fixtures) {
    test(name, context => {
        const actual = positionAt(orbit, mu, seconds);
        const difference = Math.hypot(...actual.map((coordinate, axis) => coordinate - position[axis]));
        context.diagnostic("position error: " + difference + " m");
        assert.ok(difference <= 0.01, "expected a position error within 1 cm");
    });
}
