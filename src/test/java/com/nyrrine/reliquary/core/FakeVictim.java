package com.nyrrine.reliquary.core;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless stand-in for a body that can be hurt: vanilla's hurt-immunity rule, and nothing else.
 *
 * <p>{@link LivingEntity} is an interface, so a body can be faked without a server — and the rule worth
 * faking is small. A blow is accepted, stamps a {@link #DURATION}-tick countdown and records its size; while
 * that countdown is over halfway a second blow is measured against the first and <b>dropped whole</b> unless
 * it is strictly bigger, in which case only its excess lands. That is the rule every relic that strikes twice
 * has to get past, so it is the rule the tests hold {@link HurtClock} against.
 *
 * <p>The countdown does not tick itself — {@link #tick} advances it by hand, which is what lets a test place
 * a follow-up at a chosen distance from the swing it rides on.
 */
final class FakeVictim implements InvocationHandler {

    /** What a blow stamps, and twice the window it actually protects for. */
    static final int DURATION = 20;

    int clock = 0;
    double lastHurt = 0.0;
    boolean dead = false;
    boolean valid = true;

    /** Damage that truly landed, after the immunity rule had its say. */
    double taken = 0.0;

    /** Every call the class under test made, in order. */
    final List<String> log = new ArrayList<>();

    /** Set to make the next damage() blow up, standing in for another plugin's listener throwing. */
    RuntimeException throwOnDamage;

    private final LivingEntity handle = (LivingEntity) Proxy.newProxyInstance(
            FakeVictim.class.getClassLoader(), new Class<?>[]{LivingEntity.class}, this);

    LivingEntity handle() {
        return handle;
    }

    /** Advance the countdown as the server would over {@code ticks} ticks. */
    void tick(int ticks) {
        clock = Math.max(0, clock - ticks);
    }

    /** Vanilla's rule, straight: what a blow of {@code amount} actually does to this body right now. */
    void hurt(double amount) {
        if (clock > DURATION / 2) {
            if (amount <= lastHurt) return;       // dropped whole — not reduced, dropped
            taken += amount - lastHurt;           // only the excess
            lastHurt = amount;
            return;                               // note: no re-stamp on this branch
        }
        taken += amount;
        lastHurt = amount;
        clock = DURATION;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "isDead":
                return dead;
            case "isValid":
                return valid;
            case "getNoDamageTicks":
                return clock;
            case "setNoDamageTicks":
                clock = (int) args[0];
                log.add("clock=" + clock);
                return null;
            case "damage":
                boolean sourced = args.length > 1;
                log.add("damage(" + args[0] + (sourced ? ",source)" : ")"));
                if (throwOnDamage != null) throw throwOnDamage;
                hurt((double) args[0]);
                return null;
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "FakeVictim";
            default:
                throw new UnsupportedOperationException(method.getName());
        }
    }

    /** A bare stand-in for whoever swung — only ever compared for identity. */
    static Entity attacker() {
        return (Entity) Proxy.newProxyInstance(
                FakeVictim.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeAttacker";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
