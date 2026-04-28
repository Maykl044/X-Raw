using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace Xrav.Desktop.Ui;

/// <summary>
/// Анимированный слой «частиц» в стиле iOS 26 / Vision Pro:
/// маленькие полупрозрачные эллипсы плавно поднимаются вверх с лёгким горизонтальным
/// дрейфом и пульсирующей прозрачностью. На паузу при потере видимости (минимизация и т.п.).
/// </summary>
public partial class ParticleField : UserControl
{
    private readonly List<Particle> _particles = new();
    private readonly Random _rng = new();
    private DateTime _lastTick;
    private bool _running;
    private const int Count = 38;

    public ParticleField()
    {
        InitializeComponent();
        Loaded += (_, _) => Start();
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
        SizeChanged += (_, _) => Reset();
    }

    private void Start()
    {
        if (_running) return;
        _running = true;
        _lastTick = DateTime.UtcNow;
        Reset();
        CompositionTarget.Rendering += OnFrame;
    }

    private void Stop()
    {
        if (!_running) return;
        _running = false;
        CompositionTarget.Rendering -= OnFrame;
    }

    private void Reset()
    {
        Surface.Children.Clear();
        _particles.Clear();
        if (ActualWidth <= 0 || ActualHeight <= 0) return;

        for (int i = 0; i < Count; i++)
            _particles.Add(SpawnParticle(initial: true));

        foreach (var p in _particles) Surface.Children.Add(p.Shape);
    }

    private Particle SpawnParticle(bool initial)
    {
        var radius = 2.0 + _rng.NextDouble() * 4.5;
        var x = _rng.NextDouble() * Math.Max(1.0, ActualWidth);
        var y = initial
            ? _rng.NextDouble() * Math.Max(1.0, ActualHeight)
            : ActualHeight + radius * 2;
        var driftX = (_rng.NextDouble() - 0.5) * 0.25;
        var speedY = 0.20 + _rng.NextDouble() * 0.55; // px / 16ms
        var alpha = 0.20 + _rng.NextDouble() * 0.35;
        var pulseSpeed = 0.4 + _rng.NextDouble() * 0.9;
        var pulsePhase = _rng.NextDouble() * Math.PI * 2;

        // цвет: акцент (фиолет) или secondary (циан) полупрозрачный
        var pickAccent = _rng.NextDouble() < 0.55;
        var color = pickAccent ? Color.FromArgb(255, 124, 92, 255) : Color.FromArgb(255, 24, 191, 255);

        var ellipse = new Ellipse
        {
            Width = radius * 2,
            Height = radius * 2,
            IsHitTestVisible = false,
            Fill = new SolidColorBrush(color) { Opacity = alpha },
            Effect = new System.Windows.Media.Effects.BlurEffect { Radius = 1.4, KernelType = System.Windows.Media.Effects.KernelType.Gaussian }
        };
        Canvas.SetLeft(ellipse, x);
        Canvas.SetTop(ellipse, y);
        return new Particle
        {
            Shape = ellipse,
            X = x, Y = y,
            DriftX = driftX, SpeedY = speedY,
            BaseAlpha = alpha,
            PulseSpeed = pulseSpeed,
            PulsePhase = pulsePhase,
            Radius = radius
        };
    }

    private void OnFrame(object? sender, EventArgs e)
    {
        var now = DateTime.UtcNow;
        var dtMs = (now - _lastTick).TotalMilliseconds;
        _lastTick = now;
        var dt = Math.Min(40.0, Math.Max(1.0, dtMs)) / 16.0; // нормализуем к 60 fps шагу

        for (int i = 0; i < _particles.Count; i++)
        {
            var p = _particles[i];
            p.Y -= p.SpeedY * dt;
            p.X += p.DriftX * dt;
            p.PulsePhase += 0.015 * p.PulseSpeed * dt;

            if (p.Y < -p.Radius * 2 || p.X < -p.Radius * 2 || p.X > ActualWidth + p.Radius * 2)
            {
                Surface.Children.Remove(p.Shape);
                _particles[i] = SpawnParticle(initial: false);
                Surface.Children.Add(_particles[i].Shape);
                continue;
            }

            Canvas.SetLeft(p.Shape, p.X);
            Canvas.SetTop(p.Shape, p.Y);
            var pulsate = 0.5 + Math.Sin(p.PulsePhase) * 0.5;
            if (p.Shape.Fill is SolidColorBrush sb)
                sb.Opacity = p.BaseAlpha * (0.55 + pulsate * 0.45);
        }
    }

    private sealed class Particle
    {
        public Ellipse Shape = null!;
        public double X, Y;
        public double DriftX, SpeedY;
        public double BaseAlpha;
        public double PulseSpeed, PulsePhase;
        public double Radius;
    }
}
