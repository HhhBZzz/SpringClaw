/**
 * useExecutionPulse —— 连线上的"光梭"流动(GSAP MotionPath)
 *
 * 光梭 = 沿 SVG 连线流动的黄色能量弹。触达目标节点时回调点亮(is-live),
 * 随后淡出。reduced-motion 下直接抵达(不流动)。
 *
 * 光梭是 SVG <g.bp-shuttle>(与连线在同一 SVG viewBox 内),MotionPath 沿
 * <path id="bp-edge-..."> 驱动,坐标系统一,无对齐误差。
 */
import gsap from 'gsap';
import { MotionPathPlugin } from 'gsap/MotionPathPlugin';

gsap.registerPlugin(MotionPathPlugin);

export interface PulseOptions {
  duration?: number;
  ease?: string;
  /** 光梭抵达终点时触发(用于点亮目标节点) */
  onArrive?: () => void;
}

export function useExecutionPulse() {
  function reduced() {
    return typeof window !== 'undefined'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  /** 让 shuttleEl(SVG <g>)沿 pathSelector 的路径流动到终点。返回可 await 的 tween。 */
  function pulse(shuttleEl: SVGElement, pathSelector: string, opts: PulseOptions = {}): gsap.core.Tween {
    const { duration = 0.7, ease = 'expo.inOut', onArrive } = opts;
    gsap.set(shuttleEl, { opacity: 0 });

    if (reduced()) {
      onArrive?.();
      return gsap.fromTo(shuttleEl, { opacity: 0.9 }, { opacity: 0, duration: 0.18, delay: 0.04, ease: 'power4.out' });
    }

    return gsap.to(shuttleEl, {
      duration,
      ease,
      motionPath: {
        path: pathSelector,
        align: pathSelector,
        alignOrigin: [0.5, 0.5],
        start: 0,
        end: 1
      },
      onStart: () => gsap.set(shuttleEl, { opacity: 1 }),
      onComplete: () => {
        onArrive?.();
        gsap.to(shuttleEl, { opacity: 0, duration: 0.18, ease: 'power1.out', overwrite: 'auto' });
      }
    });
  }

  return { pulse, reduced };
}
