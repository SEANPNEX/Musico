using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using MusicoWindows.Models;
using MusicoWindows.Views;
using Track = MusicoWindows.Models.Track;

namespace MusicoWindows.Controls;

/// <summary>
/// Shared WPF helpers for Extended multi-selection, Ctrl+A / Esc shortcuts,
/// and selection-mode toggling across track lists and collection views.
/// </summary>
internal static class MultiSelectUiHelper
{
    public static bool IsSelectionModifierHeld() =>
        (Keyboard.Modifiers & (ModifierKeys.Control | ModifierKeys.Shift)) != 0;

    public static bool HandleListKeyboardShortcuts(
        ListBox listBox,
        KeyEventArgs e,
        Action clearSelection)
    {
        if (Keyboard.FocusedElement is TextBoxBase or PasswordBox)
        {
            return false;
        }

        if (e.Key == Key.A && Keyboard.Modifiers == ModifierKeys.Control && listBox.Items.Count > 0)
        {
            listBox.SelectAll();
            e.Handled = true;
            return true;
        }

        if (e.Key == Key.Escape && listBox.SelectedItems.Count > 0)
        {
            clearSelection();
            e.Handled = true;
            return true;
        }

        return false;
    }

    public static void ToggleListBoxItemSelection(ListBox listBox, object item)
    {
        if (listBox.ItemContainerGenerator.ContainerFromItem(item) is ListBoxItem container)
        {
            container.IsSelected = !container.IsSelected;
        }
        else if (listBox.SelectedItems.Contains(item))
        {
            listBox.SelectedItems.Remove(item);
        }
        else
        {
            listBox.SelectedItems.Add(item);
        }
    }

    public static void SyncTrackRowControls(ListBox listBox, bool selectionModeActive)
    {
        var selectedSet = new HashSet<object>(listBox.SelectedItems.Cast<object>());
        foreach (object item in listBox.Items)
        {
            if (item is TrackRowControl row)
            {
                object captured = item;
                row.SetSelectionState(
                    selectedSet.Contains(item),
                    selectionModeActive,
                    () => ToggleListBoxItemSelection(listBox, captured));
            }
        }
    }

    public static void OpenBatchTagEdit(ViewContext ctx, IReadOnlyList<Track> tracks)
    {
        var editable = tracks
            .Where(t => !t.IsEphemeral && !Track.HasEphemeralIdPrefix(t.Id))
            .ToList();
        if (editable.Count == 0) return;

        if (ctx.OpenBatchTagEdit is not null)
        {
            ctx.OpenBatchTagEdit(editable);
        }
        else if (Application.Current?.MainWindow is MainWindow mw)
        {
            mw.ShowBatchTagEdit(editable);
        }
    }
}
