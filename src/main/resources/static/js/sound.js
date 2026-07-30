/**
 * 音效系统 - 使用 Web Audio API 生成提示音
 * 支持普通和紧急两种音效，防音效轰炸机制
 */
var SoundSystem = (function() {
    var audioContext = null;
    var _lastPlayTime = 0;
    var MIN_PLAY_INTERVAL = 200; // 最小播放间隔（毫秒）

    /**
     * 初始化 AudioContext
     * 注意：必须在用户交互后调用（浏览器自动播放策略）
     */
    function init() {
        try {
            if (!audioContext) {
                var AudioContext = window.AudioContext || window.webkitAudioContext;
                if (AudioContext) {
                    audioContext = new AudioContext();
                }
            }
            if (audioContext && audioContext.state === 'suspended') {
                audioContext.resume();
            }
        } catch (e) {
            console.warn('Web Audio API 不支持:', e.message);
        }
    }

    /**
     * 播放单个音符
     * @param {number} frequency - 频率（Hz）
     * @param {number} startTime - 开始时间
     * @param {number} duration - 持续时间（秒）
     * @param {number} volume - 音量（0-1）
     */
    function playNote(frequency, startTime, duration, volume) {
        if (!audioContext) return;

        try {
            var oscillator = audioContext.createOscillator();
            var gainNode = audioContext.createGain();

            oscillator.type = 'sine';
            oscillator.frequency.setValueAtTime(frequency, startTime);

            // 音量渐变（避免爆音）
            gainNode.gain.setValueAtTime(0, startTime);
            gainNode.gain.linearRampToValueAtTime(volume, startTime + 0.01);
            gainNode.gain.setValueAtTime(volume, startTime + duration - 0.02);
            gainNode.gain.linearRampToValueAtTime(0, startTime + duration);

            oscillator.connect(gainNode);
            gainNode.connect(audioContext.destination);

            oscillator.start(startTime);
            oscillator.stop(startTime + duration);
        } catch (e) {
            console.warn('播放音符失败:', e.message);
        }
    }

    /**
     * 播放普通提示音（双音：C5 → E5）
     * 间隔 0.15 秒，每个音符持续 0.2 秒，音量 0.3
     */
    function playNormal() {
        if (!canPlay()) return;

        try {
            init();
            if (!audioContext) return;

            var volume = 0.3;
            var now = audioContext.currentTime;

            // C5 = 523.25 Hz, E5 = 659.25 Hz
            playNote(523.25, now, 0.2, volume);
            playNote(659.25, now + 0.35, 0.2, volume); // 0.2 + 0.15 = 0.35

            _lastPlayTime = Date.now();
        } catch (e) {
            console.warn('播放普通音效失败:', e.message);
        }
    }

    /**
     * 播放紧急提示音（三连音：G5 → E5 → C5）
     * 节奏更快：间隔 0.1 秒，每个音符持续 0.15 秒，音量 0.3
     */
    function playUrgent() {
        if (!canPlay()) return;

        try {
            init();
            if (!audioContext) return;

            var volume = 0.3;
            var now = audioContext.currentTime;

            // G5 = 783.99 Hz, E5 = 659.25 Hz, C5 = 523.25 Hz
            playNote(783.99, now, 0.15, volume);
            playNote(659.25, now + 0.25, 0.15, volume); // 0.15 + 0.1 = 0.25
            playNote(523.25, now + 0.5, 0.15, volume);   // 0.25 + 0.15 + 0.1 = 0.5

            _lastPlayTime = Date.now();
        } catch (e) {
            console.warn('播放紧急音效失败:', e.message);
        }
    }

    /**
     * 根据优先级自动选择音效
     * @param {string} priority - 优先级：URGENT/HIGH/NORMAL/LOW
     */
    function play(priority) {
        var p = (priority || 'NORMAL').toUpperCase();

        switch (p) {
            case 'URGENT':
            case 'HIGH':
                playUrgent();
                break;
            case 'NORMAL':
            case 'LOW':
            default:
                playNormal();
                break;
        }
    }

    /**
     * 检查是否可以播放（防音效轰炸）
     * @returns {boolean}
     */
    function canPlay() {
        var now = Date.now();
        if (now - _lastPlayTime < MIN_PLAY_INTERVAL) {
            return false;
        }
        return true;
    }

    // 暴露公共接口
    return {
        init: init,
        playNormal: playNormal,
        playUrgent: playUrgent,
        play: play,
        _lastPlayTime: _lastPlayTime
    };
})();

// 兼容全局访问
window.SoundSystem = SoundSystem;
