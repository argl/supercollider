/*
    SuperCollider Qt IDE
    Copyright (c) 2012 Jakob Leben & Tim Blechmann
    http://www.audiosynth.com

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
*/

#include "overlay.hpp"
#include "editor.hpp"
#include "../../core/main.hpp"

#include <QTextDocument>
#include <QTextLayout>
#include <QTextBlock>
#include <QPainter>
#include <QPropertyAnimation>
#include <QtCore/qmath.h>
#include <QDebug>

namespace ScIDE {

void CodeEditor::blinkCode( const QTextCursor & c )
{
    if( !c.document() || !c.hasSelection() ) return;

    Settings::Manager *settings = Main::settings();
    settings->beginGroup("IDE/editor/colors");
    QTextCharFormat evalCodeTextFormat = settings->value("evaluatedCode").value<QTextCharFormat>();
    settings->endGroup();

    QTextDocument *doc = c.document();

    int startPos = c.selectionStart();
    int endPos = c.selectionEnd();
    QTextBlock startBlock = doc->findBlock(startPos);
    QTextBlock endBlock = doc->findBlock(endPos);
    startPos -= startBlock.position();
    endPos -= endBlock.position();

    // Get the bounds of visible blocks within the cursor's selection:

    QTextBlock block = firstVisibleBlock();
    int idx = block.blockNumber();
    int sidx = startBlock.blockNumber();

    QTextBlock firstBlock, lastBlock;
    firstBlock = lastBlock = block;

    QRectF geom = blockBoundingGeometry(block).translated(contentOffset());
    qreal top = geom.top();
    qreal bottom = top;
    qreal width=0;

    while(block.isValid() && bottom < viewport()->rect().height())
    {
        if(block.isVisible())
        {
            QTextLayout *l = block.layout();
            QRectF r = l->boundingRect();
            bottom += r.height();
            if(idx < sidx) {
                // Block not within the selection. Will skip it.
                top = bottom;
            }
            else {
                // Block within the selection.
                width = qMax(width, l->maximumWidth() + r.left());
            }
        }

        if(block == endBlock) break;

        block = block.next();
        ++idx;
        if(top == bottom)
            firstBlock = block;
    }

    lastBlock = block;

    if(bottom == top) {
        //qDebug("no visible block.");
        return;
    }

    // Construct a pixmap to render the code on:

    QPixmap pix( QSize(qCeil(width), qCeil(bottom - top)) );
    pix.fill(QColor(0,0,0,0));

    // Render the visible blocks:

    QPainter painter(&pix);
    QVector<QTextLayout::FormatRange> selections;
    block = firstBlock;
    int y=0;
    while( block.isValid() )
    {
        if (block.isVisible())
        {
            QRectF blockRect = block.layout()->boundingRect();

            // Use extra char formatting to hide code outside of selection
            // and modify the appearance of selected code:

            QTextLayout::FormatRange range;
            selections.clear();

            int start = 0;
            if(block == startBlock) {
                range.start = 0;
                range.length = startPos;
                range.format.setForeground(QColor(0,0,0,0));
                range.format.setBackground(Qt::NoBrush);
                selections.append(range);
                start = startPos;
            }

            range.start = start;
            range.length = (block == endBlock ? endPos : block.length() - 1) - range.start;
            range.format = evalCodeTextFormat;
            selections.append(range);

            if(block == endBlock) {
                range.start = range.start + range.length;
                range.length = block.length() - 1 - range.start;
                range.format.setForeground(QColor(0,0,0,0));
                range.format.setBackground(Qt::NoBrush);
                selections.append(range);
            }

            block.layout()->draw(&painter, QPointF(0,y), selections);

            y += blockRect.height();
        }

        if(block == lastBlock) break;

        block = block.next();
    }

    // Create an overlay item to display the pixmap, and animate it:

    CodeFragmentOverlay *item = new CodeFragmentOverlay();
    item->setPixmap(pix);
    item->setPos(geom.left(), top);

    mOverlay->addItem(item);

    QPropertyAnimation *anim = new QPropertyAnimation(item, "opacity", item);
    anim->setDuration(mBlinkDuration);
    anim->setStartValue(1.0);
    anim->setEndValue(0.0);
    anim->setEasingCurve( QEasingCurve::InCubic );
    anim->start();

    connect(anim, SIGNAL(finished()), item, SLOT(deleteLater()));
}

} // namespace ScIDE