import { useEffect } from "react";
import confetti from "canvas-confetti";

// Triggered by completing the final due session, never by simply opening the app.
export function CompletionFireworks({ trigger }: { trigger: number }) {
  useEffect(() => {
    if (
      !trigger ||
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    )
      return;
    const canvas = document.createElement("canvas");
    canvas.setAttribute("aria-hidden", "true");
    canvas.dataset.completionFireworks = "true";
    Object.assign(canvas.style, {
      position: "fixed",
      inset: "0",
      width: "100%",
      height: "100%",
      pointerEvents: "none",
      zIndex: "100",
    });
    document.body.appendChild(canvas);
    const fire = confetti.create(canvas, { resize: true });
    const timers: ReturnType<typeof setTimeout>[] = [];
    const bursts = [
      { x: 0.25, y: 0.3 },
      { x: 0.75, y: 0.4 },
      { x: 0.5, y: 0.2 },
      { x: 0.2, y: 0.5 },
      { x: 0.8, y: 0.25 },
    ];
    bursts.forEach((origin, index) =>
      timers.push(
        setTimeout(() => {
          void fire({
            particleCount: 75,
            spread: 360,
            startVelocity: 32,
            gravity: 0.65,
            ticks: 140,
            origin,
            colors: ["#7c3aed", "#fbbf24", "#38bdf8", "#34d399", "#fb7185"],
            disableForReducedMotion: true,
          });
        }, index * 400),
      ),
    );
    timers.push(
      setTimeout(() => {
        fire.reset();
        canvas.remove();
      }, 5000),
    );
    return () => {
      timers.forEach(clearTimeout);
      fire.reset();
      canvas.remove();
    };
  }, [trigger]);
  return null;
}
