/*
 * Copyright 2026 MEDA
 *
 * This file is part of Modelio.
 *
 * Modelio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.modelio.mcp.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.modelio.api.modelio.IModelioServices;
import org.modelio.api.modelio.IModelioServicesRegistry;
import org.modelio.api.modelio.diagram.IDiagramGraphic;
import org.modelio.api.modelio.diagram.IDiagramHandle;
import org.modelio.api.modelio.diagram.IDiagramLink;
import org.modelio.api.modelio.diagram.IDiagramNode;
import org.modelio.api.modelio.diagram.IDiagramService;
import org.modelio.api.modelio.diagram.dg.IDiagramDG;
import org.modelio.api.module.context.IModuleContext;
import org.modelio.mcp.server.McpProtocol.ToolException;
import org.modelio.metamodel.diagrams.AbstractDiagram;
import org.modelio.vcore.session.api.ICoreSession;
import org.modelio.vcore.session.api.transactions.ITransaction;
import org.modelio.vcore.smkernel.mapi.MObject;

/**
 * Diagram contents: list what a diagram shows and unmask (draw) model elements in it.
 * <p>
 * Setting a model reference such as <code>ArchimateView.Represented</code> does not draw anything: the graphics of a
 * diagram live in its own data and are edited through the Modelio diagram API ({@link IDiagramHandle}).
 * Everything runs in the SWT UI thread (the caller ensures it).
 */
class DiagramEdits {
    /** Horizontal step between elements unmasked in one call. */
    private static final int STEP_X = 180;

    private static final int MARGIN = 50;

    /** Gaps smaller than this when distributing (nodes overlapping) are replaced by {@link #DEFAULT_GAP}. */
    private static final int MIN_GAP = 10;

    private static final int DEFAULT_GAP = 40;

    private final ObjectMapper mapper;

    private final IEclipseContext context;

    private final ModelQueries queries;

    DiagramEdits(ObjectMapper mapper, IEclipseContext context, ModelQueries queries) {
        this.mapper = mapper;
        this.context = context;
        this.queries = queries;
    }

    // ------------------------------------------------------------------ tools

    JsonNode getDiagramContents(JsonNode args) {
        AbstractDiagram diagram = requireDiagram(args);
        ObjectNode r = this.mapper.createObjectNode();
        r.set("diagram", this.queries.summary(diagram));
        ArrayNode nodes = r.putArray("nodes");
        ArrayNode links = r.putArray("links");
        try (IDiagramHandle dh = diagramService().getDiagramHandle(diagram)) {
            IDiagramDG root = dh.getDiagramNode();
            for (IDiagramNode n : root.getNodes()) {
                addNode(nodes, n, null);
            }
            for (IDiagramLink l : root.getLinks()) {
                ObjectNode o = graphic(links, l);
                if (l.getFrom() != null && l.getFrom().getElement() != null) {
                    o.put("from_id", l.getFrom().getElement().getUuid());
                }
                if (l.getTo() != null && l.getTo().getElement() != null) {
                    o.put("to_id", l.getTo().getElement().getUuid());
                }
            }
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("Cannot read diagram " + describe(diagram) + ": " + e);
        }
        return r;
    }

    JsonNode addToDiagram(JsonNode args) {
        AbstractDiagram diagram = requireDiagram(args);
        if (!diagram.isModifiable()) {
            throw new ToolException(describe(diagram) + " is not modifiable (read-only fragment or library?).");
        }
        List<MObject> elements = new ArrayList<>();
        for (JsonNode id : args.path("element_ids")) {
            elements.add(this.queries.requireElement(id.asText()));
        }
        if (elements.isEmpty()) {
            throw new ToolException("Missing required argument 'element_ids'.");
        }
        boolean explicitPos = args.has("x") || args.has("y");
        int x0 = args.path("x").asInt(MARGIN);
        int y0 = args.path("y").asInt(MARGIN);

        ICoreSession session = this.queries.requireProject().getSession();
        IDiagramService ds = diagramService();
        ArrayNode added = this.mapper.createArrayNode();
        ArrayNode skipped = this.mapper.createArrayNode();
        try (ITransaction t = session.getTransactionSupport().createTransaction("MCP: add to diagram " + diagram.getName())) {
            try (IDiagramHandle dh = ds.getDiagramHandle(diagram)) {
                if (!explicitPos) {
                    y0 = belowContent(dh.getDiagramNode());
                }
                int x = x0;
                for (MObject el : elements) {
                    if (!dh.getDiagramGraphics(el).isEmpty()) {
                        skipped.add(reason(el, "already shown in the diagram"));
                        continue;
                    }
                    List<IDiagramGraphic> gs = dh.unmask(el, x, y0);
                    if (gs == null || gs.isEmpty()) {
                        skipped.add(reason(el, "this diagram cannot show it"));
                        continue;
                    }
                    ObjectNode o = this.queries.summary(el);
                    o.put("x", x);
                    o.put("y", y0);
                    added.add(o);
                    x += STEP_X;
                }
                dh.save();
            }
            t.commit();
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("Cannot add to diagram " + describe(diagram) + ": " + e);
        }
        ObjectNode r = this.mapper.createObjectNode();
        r.set("diagram", this.queries.summary(diagram));
        r.put("added_count", added.size());
        r.set("added", added);
        r.set("skipped", skipped);
        return r;
    }

    JsonNode moveInDiagram(JsonNode args) {
        AbstractDiagram diagram = requireModifiableDiagram(args);
        JsonNode moves = args.path("moves");
        if (!moves.isArray() || moves.isEmpty()) {
            throw new ToolException("Missing required argument 'moves'.");
        }
        Map<MObject, JsonNode> requested = new LinkedHashMap<>();
        for (JsonNode m : moves) {
            requested.put(this.queries.requireElement(ModelQueries.requiredText(m, "element_id")), m);
        }
        return arrange(diagram, "move in", requested.keySet(), nodes -> {
            Map<IDiagramNode, Rectangle> targets = new LinkedHashMap<>();
            for (Map.Entry<MObject, IDiagramNode> e : nodes.entrySet()) {
                JsonNode m = requested.get(e.getKey());
                Rectangle b = e.getValue().getBounds();
                targets.put(e.getValue(), new Rectangle(m.path("x").asInt(b.x), m.path("y").asInt(b.y),
                        m.path("width").asInt(b.width), m.path("height").asInt(b.height)));
            }
            return targets;
        });
    }

    JsonNode alignInDiagram(JsonNode args) {
        AbstractDiagram diagram = requireModifiableDiagram(args);
        List<MObject> elements = new ArrayList<>();
        for (JsonNode id : args.path("element_ids")) {
            elements.add(this.queries.requireElement(id.asText()));
        }
        if (elements.size() < 2) {
            throw new ToolException("'element_ids' needs at least 2 elements.");
        }
        String align = args.path("align").asText("").toLowerCase();
        String distribute = args.path("distribute").asText("").toLowerCase();
        if (!align.isEmpty() && !List.of("left", "center", "right", "top", "middle", "bottom").contains(align)) {
            throw new ToolException("Invalid 'align': " + align + " (expected left, center, right, top, middle or bottom).");
        }
        if (!distribute.isEmpty() && !List.of("horizontal", "vertical").contains(distribute)) {
            throw new ToolException("Invalid 'distribute': " + distribute + " (expected horizontal or vertical).");
        }
        if (align.isEmpty() && distribute.isEmpty()) {
            throw new ToolException("Give 'align' and/or 'distribute'.");
        }
        MObject anchorEl = args.hasNonNull("anchor_id") ? this.queries.requireElement(args.get("anchor_id").asText()) : elements.get(0);
        if (!elements.contains(anchorEl)) {
            elements.add(0, anchorEl);
        }
        Integer spacing = args.hasNonNull("spacing") ? args.get("spacing").asInt() : null;

        return arrange(diagram, "align in", elements, nodes -> {
            Map<IDiagramNode, Rectangle> targets = new LinkedHashMap<>();
            for (IDiagramNode n : nodes.values()) {
                targets.put(n, n.getBounds().getCopy());
            }
            if (!nodes.containsKey(anchorEl)) {
                throw new ToolException("The anchor " + describe(anchorEl) + " is not drawn as a node in the diagram.");
            }
            Rectangle anchor = targets.get(nodes.get(anchorEl));
            for (Rectangle r : targets.values()) {
                switch (align) {
                case "left" -> r.x = anchor.x;
                case "center" -> r.x = anchor.x + (anchor.width - r.width) / 2;
                case "right" -> r.x = anchor.right() - r.width;
                case "top" -> r.y = anchor.y;
                case "middle" -> r.y = anchor.y + (anchor.height - r.height) / 2;
                case "bottom" -> r.y = anchor.bottom() - r.height;
                default -> {
                    // no alignment requested
                }
                }
            }
            if (!distribute.isEmpty()) {
                distribute(new ArrayList<>(targets.values()), "vertical".equals(distribute), spacing);
            }
            return targets;
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Spread the rectangles along one axis, keeping their current order on it. */
    private static void distribute(List<Rectangle> rects, boolean vertical, Integer spacing) {
        rects.sort(Comparator.comparingInt(r -> vertical ? r.y : r.x));
        int count = rects.size();
        int start = vertical ? rects.get(0).y : rects.get(0).x;
        int gap;
        if (spacing != null) {
            gap = spacing;
        } else {
            Rectangle last = rects.get(count - 1);
            int span = (vertical ? last.bottom() : last.right()) - start;
            int used = rects.stream().mapToInt(r -> vertical ? r.height : r.width).sum();
            gap = (span - used) / (count - 1);
            if (gap < MIN_GAP) {
                gap = DEFAULT_GAP;
            }
        }
        int pos = start;
        for (Rectangle r : rects) {
            if (vertical) {
                r.y = pos;
                pos += r.height + gap;
            } else {
                r.x = pos;
                pos += r.width + gap;
            }
        }
    }

    /**
     * Find the top level node drawing each element, let <code>layout</code> compute their new bounds, apply them in one
     * transaction and report the resulting bounds.
     */
    private JsonNode arrange(AbstractDiagram diagram, String verb, Collection<MObject> elements,
            Function<Map<MObject, IDiagramNode>, Map<IDiagramNode, Rectangle>> layout) {
        ICoreSession session = this.queries.requireProject().getSession();
        IDiagramService ds = diagramService();
        ArrayNode moved = this.mapper.createArrayNode();
        ArrayNode skipped = this.mapper.createArrayNode();
        try (ITransaction t = session.getTransactionSupport().createTransaction("MCP: " + verb + " diagram " + diagram.getName())) {
            try (IDiagramHandle dh = ds.getDiagramHandle(diagram)) {
                Map<MObject, IDiagramNode> nodes = new LinkedHashMap<>();
                for (MObject el : elements) {
                    IDiagramNode n = mainNode(dh, el);
                    if (n == null) {
                        skipped.add(reason(el, "not drawn as a node in the diagram"));
                    } else {
                        nodes.put(el, n);
                    }
                }
                if (nodes.isEmpty()) {
                    throw new ToolException("None of the given elements is drawn as a node in " + describe(diagram) + ".");
                }
                Map<IDiagramNode, Rectangle> targets = layout.apply(nodes);
                for (Map.Entry<MObject, IDiagramNode> e : nodes.entrySet()) {
                    IDiagramNode n = e.getValue();
                    Rectangle before = n.getBounds().getCopy();
                    Rectangle target = targets.get(n);
                    Map<IDiagramNode, Rectangle> satellites = new LinkedHashMap<>();
                    for (IDiagramNode s : n.getNodes(IDiagramNode.Role.SATELLITE)) {
                        if (s.getBounds() != null) {
                            satellites.put(s, s.getBounds().getCopy());
                        }
                    }
                    if (target.width != before.width || target.height != before.height) {
                        n.setSize(target.width, target.height);
                    }
                    n.setLocation(target.x, target.y);
                    Rectangle after = n.getBounds();
                    // Make satellite labels (e.g. the name under an icon) follow their node when the move left them behind.
                    int dx = after.x - before.x;
                    int dy = after.y - before.y;
                    if (dx != 0 || dy != 0) {
                        for (Map.Entry<IDiagramNode, Rectangle> s : satellites.entrySet()) {
                            if (s.getKey().getBounds().getTopLeft().equals(s.getValue().getTopLeft())) {
                                s.getKey().setLocation(s.getValue().x + dx, s.getValue().y + dy);
                            }
                        }
                    }
                    ObjectNode o = this.queries.summary(e.getKey());
                    o.put("x", after.x);
                    o.put("y", after.y);
                    o.put("width", after.width);
                    o.put("height", after.height);
                    if (!after.equals(target)) {
                        o.put("note", "Modelio adapted the requested bounds " + target.x + "," + target.y + " " + target.width + "x" + target.height);
                    }
                    moved.add(o);
                }
                dh.save();
            }
            t.commit();
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("Cannot " + verb + " diagram " + describe(diagram) + ": " + e);
        }
        ObjectNode r = this.mapper.createObjectNode();
        r.set("diagram", this.queries.summary(diagram));
        r.set("nodes", moved);
        r.set("skipped", skipped);
        return r;
    }

    /** The node drawing the element, preferring the outermost one (not a label or port). */
    private static IDiagramNode mainNode(IDiagramHandle dh, MObject el) {
        IDiagramNode best = null;
        for (IDiagramGraphic g : dh.getDiagramGraphics(el)) {
            if (g instanceof IDiagramNode n && n.getBounds() != null) {
                if (best == null || !(n.getParent() instanceof IDiagramNode p && el.equals(p.getElement()))) {
                    best = n;
                }
            }
        }
        return best;
    }

    private AbstractDiagram requireModifiableDiagram(JsonNode args) {
        AbstractDiagram diagram = requireDiagram(args);
        if (!diagram.isModifiable()) {
            throw new ToolException(describe(diagram) + " is not modifiable (read-only fragment or library?).");
        }
        return diagram;
    }

    private AbstractDiagram requireDiagram(JsonNode args) {
        MObject obj = this.queries.requireElement(ModelQueries.requiredText(args, "diagram_id"));
        if (!(obj instanceof AbstractDiagram)) {
            throw new ToolException(describe(obj) + " is not a diagram (use find_elements with metaclass 'AbstractDiagram').");
        }
        return (AbstractDiagram) obj;
    }

    private IDiagramService diagramService() {
        IModelioServices services = null;
        IModuleContext mc = this.context.get(IModuleContext.class);
        if (mc != null) {
            services = mc.getModelioServices();
        }
        if (services == null && this.context.get(IModelioServicesRegistry.class) instanceof IModelioServices s) {
            services = s;
        }
        IDiagramService ds = services != null ? services.getDiagramService() : null;
        if (ds == null) {
            throw new ToolException("The Modelio diagram service is not available (is a project opened?).");
        }
        return ds;
    }

    /** Y coordinate just below the top level nodes of the diagram. */
    private static int belowContent(IDiagramDG root) {
        int bottom = 0;
        for (IDiagramNode n : root.getNodes()) {
            Rectangle b = n.getBounds();
            if (b != null) {
                bottom = Math.max(bottom, b.bottom());
            }
        }
        return bottom == 0 ? MARGIN : bottom + MARGIN;
    }

    private void addNode(ArrayNode out, IDiagramNode n, String parentId) {
        ObjectNode o = graphic(out, n);
        Rectangle b = n.getBounds();
        if (b != null) {
            o.put("x", b.x);
            o.put("y", b.y);
            o.put("width", b.width);
            o.put("height", b.height);
        }
        if (parentId != null) {
            o.put("parent_id", parentId);
        }
        String id = n.getElement() != null ? n.getElement().getUuid() : parentId;
        for (IDiagramNode c : n.getNodes()) {
            addNode(out, c, id);
        }
    }

    private ObjectNode graphic(ArrayNode out, IDiagramGraphic g) {
        MObject el = g.getElement();
        ObjectNode o = el != null ? this.queries.summary(el) : this.mapper.createObjectNode();
        if (el == null) {
            o.put("name", g.getName());
            o.put("ghost", true);
        }
        out.add(o);
        return o;
    }

    private ObjectNode reason(MObject el, String why) {
        ObjectNode o = this.queries.summary(el);
        o.put("reason", why);
        return o;
    }

    private static String describe(MObject o) {
        return "'" + o.getName() + "' [" + o.getMClass().getName() + "]";
    }

}
